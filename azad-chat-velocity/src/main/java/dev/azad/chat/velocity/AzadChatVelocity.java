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
import java.util.regex.Pattern;

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

    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9A-Fa-f]{6}");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private RedisBus bus;
    private MiniYaml config;
    private TabTags tags;

    private String channel = "azad-chat";
    private String chatFormat = "%name%&7: &f%message%";
    private String deathFormat = "%name%&7 %message%";
    private String joinFormat = "&8[&a+&8] %name%";
    private String quitFormat = "&8[&c-&8] %name%";
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
        String nickFile = config.get("nickname.file", "/home/minecraft/nijijoji/mc01/plugins/ChineseName/config.yml");
        Path nickPath = Path.of(nickFile).toAbsolutePath().normalize();
        if (!config.getBool("nickname.enabled", false)) {
            logger.info("[Azad-Chat] 中文昵称已禁用（聊天与 TAB 均使用原始玩家 ID）");
            logger.info("[Azad-Chat] 已启用，频道={}，TAB 服务器标签={}", channel, tags.all());
            return;
        }
        Thread hook = new Thread(() -> {
            for (int attempt = 1; attempt <= 20; attempt++) {
                if (TabNickHook.tryInstall(logger, nickPath)) {
                    return;
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            logger.warn("[Azad-Chat] 20 次尝试后仍未注册到 TAB（TAB 未安装或版本不兼容），TAB 将显示原始 ID");
        }, "azad-chat-tab-hook");
        hook.setDaemon(true);
        hook.start();
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
        chatFormat = firstNonNull(
                config.get("render.chat-format", ""),
                config.get("format.chat", ""),
                chatFormat);
        deathFormat = firstNonNull(
                config.get("render.death-format", ""),
                config.get("format.death", ""),
                deathFormat);
        joinFormat = firstNonNull(
                config.get("render.join-format", ""),
                config.get("format.join", ""),
                joinFormat);
        quitFormat = firstNonNull(
                config.get("render.quit-format", ""),
                config.get("format.quit", ""),
                quitFormat);
        logToConsole = config.getBool("render.log-to-console", true);
        nicknameMaxLength = config.getInt("nickname-maxlength", 24);
        String groups = config.get("tab.groups-yml", "../tab/groups.yml");
        Path groupsPath = dataDirectory.resolve(groups).normalize();
        tags = TabTags.load(groupsPath, config.get("tab.fallback-tag", ""));
    }

    /** 拦截代理侧聊天（一般不会走到，聊天由子服采集），本地不再原样广播。 */
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
        payload.body = "{\"text\":" + quote(event.getMessage()) + "}";
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
        if (payload.type == null || payload.type.isBlank()) {
            return;
        }
        Component message = render(payload);
        if (message == null) {
            return;
        }
        for (Player viewer : proxy.getAllPlayers()) {
            viewer.sendMessage(message);
        }
        if (logToConsole) {
            proxy.getConsoleCommandSource().sendMessage(message);
        }
    }

    private Component render(Payload payload) {
        UUID uuid = parseUuid(payload.uuid);
        String nick = nick(payload);
        String tag = resolveTag(tags.template(payload.server), uuid);
        String nameSection = tag + nick + (tag.contains("%luckperms-suffix%") ? "" : suffix(uuid));
        String nameOnly = tag + nick + suffix(uuid);

        switch (payload.type) {
            case Payload.CHAT -> {
                Component body = deserializeComponent(payload.body);
                if (body == null) {
                    return null;
                }
                return splice(chatFormat, "%name%", nameSection, body);
            }
            case Payload.DEATH -> {
                Component body = deserializeComponent(payload.body);
                if (body == null) {
                    return null;
                }
                String fmt = deathFormat.contains("%name%") ? deathFormat : deathFormat;
                return splice(fmt, "%name%", nameOnly, body);
            }
            // 进服 / 退服 / 切服提示由 ProxyMessages 负责，这里不再渲染，避免重复
            case Payload.JOIN, Payload.QUIT -> {
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** 把格式串里的 %name% 换成玩家段落，%message% 换成真正的 Component（保留 hover/translate）。 */
    private static Component splice(String format, String nameToken, String nameValue, Component body) {
        String fmt = format == null ? "" : format;
        String withName = fmt.replace(nameToken, nameValue);
        int idx = withName.indexOf("%message%");
        if (idx < 0) {
            return legacy(withName);
        }
        String head = withName.substring(0, idx);
        String tail = withName.substring(idx + "%message%".length());
        Component result = legacy(head).append(body);
        if (!tail.isEmpty()) {
            result = result.append(legacy(tail));
        }
        return result;
    }

    private static String replaceName(String format, String nameSection) {
        String fmt = format == null ? "" : format;
        return fmt.replace("%name%", nameSection)
                .replace("%player%", nameSection.replaceAll("(?i)%luckperms-(prefix|suffix)%", "").trim())
                .replace("%message%", "");
    }

    private String nick(Payload payload) {
        String nick = payload.nick == null || payload.nick.isBlank() ? payload.name : payload.nick;
        if (nick == null) {
            nick = "";
        }
        if (nick.length() > nicknameMaxLength) {
            nick = nick.substring(0, nicknameMaxLength);
        }
        return nick;
    }

    /** TAB 的 tabprefix 里带 %luckperms-prefix%，聊天里必须自己替换成真实值，否则会原样打出来。 */
    private String resolveTag(String template, UUID uuid) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String resolved = template;
        if (resolved.contains("%luckperms-prefix%")) {
            resolved = resolved.replace("%luckperms-prefix%", meta(uuid, true));
        } else {
            resolved = meta(uuid, true) + resolved;
        }
        if (resolved.contains("%luckperms-suffix%")) {
            resolved = resolved.replace("%luckperms-suffix%", meta(uuid, false));
        }
        return resolved;
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

    private static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(recolor(s));
    }

    /** TAB 配置里的颜色代码是 &，转成 § 便于 Adventure 解析；同时把 #RRGGBB 转成 §x§R§R§G§G§B§B。 */
    private static String recolor(String s) {
        if (s == null) {
            return "";
        }
        String out = s.replace('&', '§');
        StringBuilder sb = new StringBuilder(out.length() + 16);
        int i = 0;
        while (i < out.length()) {
            char c = out.charAt(i);
            boolean sectionHex = c == '§' && i + 7 < out.length() && out.charAt(i + 1) == '#';
            boolean bareHex = c == '#' && (i == 0 || out.charAt(i - 1) != '§');
            int hexStart = sectionHex ? i + 2 : (bareHex ? i + 1 : -1);
            if (hexStart > 0 && hexStart + 6 <= out.length()
                    && HEX_PATTERN.matcher(out.substring(hexStart, hexStart + 6)).matches()) {
                if (sectionHex) {
                    sb.append('§');
                }
                sb.append("§x");
                for (char h : out.substring(hexStart, hexStart + 6).toCharArray()) {
                    sb.append('§').append(Character.toUpperCase(h));
                }
                i = hexStart + 6;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
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
