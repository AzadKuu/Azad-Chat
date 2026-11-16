package dev.azad.chat.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.azad.chat.common.Payload;
import dev.azad.chat.common.RedisBus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 代理侧：订阅 Redis 总线，把子服推上来的聊天 / 死亡 / 进服消息统一渲染后广播给所有在线玩家。
 */
@Plugin(
        id = "azad-chat",
        name = "Azad-Chat",
        version = "1.0.0",
        description = "跨服聊天 / 死亡消息统一渲染（Redis 总线）",
        authors = {"Azad"}
)
public final class AzadChatVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private RedisBus bus;
    private MiniYaml config;
    private TabTags tags;

    private String channel = "azad-chat";
    private String chatFormat = "%name%§f: %message%";
    private String deathFormat = "%server%§7%message%";
    private String joinFormat = "%server%§e%player% 加入了服务器";
    private String quitFormat = "%server%§e%player% 离开了服务器";
    private boolean logToConsole = true;
    private int nicknameMaxLength = 24;

    @Inject
    public AzadChatVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ensureConfigFile();
        loadConfig();
        String host = config.get("redis.host", "127.0.0.1");
        int port = config.getInt("redis.port", 6379);
        String password = config.get("redis.password", "");
        channel = config.get("redis.channel", "azad-chat");
        bus = new RedisBus(host, port, password, new BusLogger(logger));
        bus.subscribe(channel, this::onBusMessage);
        logger.info("[Azad-Chat] 已启用，频道={}，TAB 服务器标签={}", channel, tags.all());
    }

    /** Velocity 不会自动释放 jar 内资源，首次启动时把默认 config.yml 落到数据目录。 */
    private void ensureConfigFile() {
        try {
            Path dir = dataDirectory;
            if (!java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }
            Path cfg = dir.resolve("config.yml");
            if (java.nio.file.Files.isRegularFile(cfg)) {
                return;
            }
            try (java.io.InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, cfg);
                    logger.info("[Azad-Chat] 已生成默认配置文件 {}", cfg);
                }
            }
        } catch (Exception e) {
            logger.warn("[Azad-Chat] 生成默认配置失败", e);
        }
    }

    private void loadConfig() {
        try {
            config = MiniYaml.load(dataDirectory.resolve("config.yml"));
        } catch (Exception e) {
            logger.warn("[Azad-Chat] 读取 config.yml 失败，使用默认配置", e);
            config = MiniYaml.empty();
        }
        chatFormat = config.get("render.chat-format", chatFormat);
        deathFormat = config.get("render.death-format", deathFormat);
        joinFormat = config.get("render.join-format", joinFormat);
        quitFormat = config.get("render.quit-format", quitFormat);
        logToConsole = config.getBool("render.log-to-console", true);
        nicknameMaxLength = config.getInt("nickname-maxlength", 24);
        String groups = config.get("tab.groups-yml", "../tab/groups.yml");
        Path groupsPath = dataDirectory.resolve(groups).normalize();
        tags = TabTags.load(groupsPath, config.get("tab.fallback-tag", ""));
    }

    /** 拦截代理侧聊天，转成总线消息；本地不再原样广播。 */
    @Subscribe(priority = Short.MAX_VALUE)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        Payload payload = new Payload();
        payload.type = Payload.CHAT;
        payload.server = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        payload.uuid = player.getUniqueId().toString();
        payload.name = player.getUsername();
        payload.nick = "";
        payload.body = event.getMessage();
        if (bus != null) {
            bus.publish(channel, payload.toJson());
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    private void onBusMessage(String json) {
        Payload payload;
        try {
            payload = Payload.fromJson(json);
        } catch (Throwable t) {
            return;
        }
        if (payload.type == null || payload.type.isBlank() || payload.ts == 0L && payload.name.isEmpty()) {
            return;
        }
        Component message = render(payload);
        if (message == null) {
            return;
        }
        for (Player viewer : proxy.getAllPlayers()) {
            if (isEcho(payload, viewer)) {
                continue;
            }
            viewer.sendMessage(message);
        }
        if (logToConsole) {
            proxy.getConsoleCommandSource().sendMessage(message);
        }
    }

    private boolean isEcho(Payload payload, Player viewer) {
        if (!Payload.CHAT.equals(payload.type)) {
            return false;
        }
        if (!viewer.getUsername().equalsIgnoreCase(payload.name)) {
            return false;
        }
        return viewer.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName().equals(payload.server))
                .orElse(false);
    }

    private Component render(Payload payload) {
        String tag = tags.template(payload.server);
        String display = displayName(payload);
        switch (payload.type) {
            case Payload.CHAT -> {
                String line = chatFormat
                        .replace("%name%", tag + display)
                        .replace("%message%", payload.body == null ? "" : payload.body);
                return LegacyComponentSerializer.legacySection().deserialize(recolor(line));
            }
            case Payload.DEATH -> {
                Component body = deserializeComponent(payload.body);
                if (body == null) {
                    return null;
                }
                Component prefix = LegacyComponentSerializer.legacySection()
                        .deserialize(recolor(tag + deathFormat.replace("%server%", "").replace("%message%", "")));
                return prefix.append(body);
            }
            case Payload.JOIN -> {
                return LegacyComponentSerializer.legacySection()
                        .deserialize(recolor(joinFormat.replace("%server%", tag).replace("%player%", display)));
            }
            case Payload.QUIT -> {
                return LegacyComponentSerializer.legacySection()
                        .deserialize(recolor(quitFormat.replace("%server%", tag).replace("%player%", display)));
            }
            default -> {
                return null;
            }
        }
    }

    private String displayName(Payload payload) {
        String nick = payload.nick == null || payload.nick.isBlank() ? payload.name : payload.nick;
        if (nick == null) {
            nick = "";
        }
        if (nick.length() > nicknameMaxLength) {
            nick = nick.substring(0, nicknameMaxLength);
        }
        UUID uuid = parseUuid(payload.uuid);
        return prefix(uuid) + nick + suffix(uuid);
    }

    private String prefix(UUID uuid) {
        String value = meta(uuid, true);
        return value == null ? "" : value;
    }

    private String suffix(UUID uuid) {
        String value = meta(uuid, false);
        return value == null ? "" : value;
    }

    private String meta(UUID uuid, boolean prefix) {
        if (uuid == null) {
            return "";
        }
        try {
            var luckPerms = LuckPermsProvider.get();
            var user = luckPerms.getUserManager().getUser(uuid);
            if (user == null) {
                user = luckPerms.getUserManager().loadUser(uuid).join();
            }
            if (user == null) {
                return "";
            }
            String value = prefix
                    ? user.getCachedData().getMetaData().getPrefix()
                    : user.getCachedData().getMetaData().getSuffix();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Component deserializeComponent(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (Throwable t) {
            return LegacyComponentSerializer.legacySection().deserialize(json);
        }
    }

    /** TAB 配置里的颜色代码是 &，转成 § 便于 Adventure 解析。 */
    private static String recolor(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    /** 把 slf4j 适配成 java.util.logging，便于复用 common 里的 RedisBus。 */
    static final class BusLogger extends java.util.logging.Logger {

        private final Logger delegate;

        BusLogger(Logger delegate) {
            super("azad-chat", null);
            this.delegate = delegate;
        }

        @Override
        public void info(String msg) {
            delegate.info(msg);
        }

        @Override
        public void warning(String msg) {
            delegate.warn(msg);
        }

        @Override
        public void severe(String msg) {
            delegate.error(msg);
        }
    }
}
