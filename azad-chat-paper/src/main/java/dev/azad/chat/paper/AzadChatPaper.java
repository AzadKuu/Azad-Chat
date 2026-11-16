package dev.azad.chat.paper;

import dev.azad.chat.common.Payload;
import dev.azad.chat.common.RedisBus;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * 子服侧：把聊天 / 死亡 / 进服 / 退服事件推到 Redis，由 Velocity 统一渲染广播。
 */
public final class AzadChatPaper extends JavaPlugin {

    private RedisBus bus;
    private String channel = "azad-chat";
    private String serverId = "unknown";
    private boolean mirrorInject = true;
    private boolean showToConsole = true;
    private boolean joinQuit = false;
    private NameService names;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();
        names = new NameService(this);
        bus = new RedisBus(
                getConfig().getString("redis.host", "127.0.0.1"),
                getConfig().getInt("redis.port", 6379),
                getConfig().getString("redis.password", ""),
                getLogger());
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);
        if (getConfig().getBoolean("home-alias.enabled", false)) {
            String from = getConfig().getString("home-alias.from", "home");
            String to = getConfig().getString("home-alias.to", "home home");
            Bukkit.getPluginManager().registerEvents(new HomeAliasListener(this, from, to), this);
            getLogger().info("home 别名已启用：/" + from + " -> /" + to);
        }
        getLogger().info("Azad-Chat 已启用（server-id=" + serverId + "，频道=" + channel + "）");
        if (bus != null) {
            bus.publish(channel, new Payload().toJson());
        }
    }

    public void reloadLocalConfig() {
        reloadConfig();
        channel = getConfig().getString("redis.channel", "azad-chat");
        serverId = resolveServerId();
        mirrorInject = getConfig().getBoolean("render.mirror-inject", true);
        showToConsole = getConfig().getBoolean("render.show-to-console", true);
        joinQuit = getConfig().getBoolean("events.join-quit", false);
    }

    private String resolveServerId() {
        String id = getConfig().getString("server.id", "auto");
        if (id != null && !id.isBlank() && !"auto".equalsIgnoreCase(id)) {
            return id;
        }
        int port = Bukkit.getServer().getPort();
        String mapped = getConfig().getString("server.port-map." + port);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return "srv" + port;
    }

    public void publish(Payload payload) {
        if (bus != null) {
            bus.publish(channel, payload.toJson());
        }
    }

    @Override
    public void onDisable() {
        if (bus != null) {
            bus.close();
        }
        getLogger().log(Level.INFO, "Azad-Chat 已关闭");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("[Azad-Chat] server-id=" + serverId + "，频道=" + channel
                    + "，mirror-inject=" + mirrorInject + "，昵称=" + (names == null ? "未初始化" : names.describe()));
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            reloadLocalConfig();
            if (names != null) {
                names.reload();
            }
            sender.sendMessage("[Azad-Chat] 配置已重载");
            return true;
        }
        return false;
    }

    public boolean mirrorInject() {
        return mirrorInject;
    }

    public boolean eventsJoinQuit() {
        return joinQuit;
    }

    public boolean showToConsole() {
        return showToConsole;
    }

    public String serverId() {
        return serverId;
    }

    public NameService names() {
        return names;
    }
}
