package dev.azad.chat.paper;

import dev.azad.chat.common.Payload;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 采集聊天 / 死亡 / 进服 / 退服，全部推到 Redis 总线，由代理统一渲染。
 */
public final class ChatListener implements Listener {

    private final AzadChatPaper plugin;

    public ChatListener(AzadChatPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Payload payload = base(player, Payload.CHAT);
        payload.body = toJson(event.message());
        plugin.publish(payload);
        if (plugin.mirrorInject()) {
            try {
                event.viewers().clear();
                event.viewers().add(player);
            } catch (Throwable ignored) {
                // 注入失败就退回原样广播，最多是本地重复一条
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Component death = event.deathMessage();
        if (death == null) {
            return;
        }
        Payload payload = base(event.getEntity(), Payload.DEATH);
        payload.body = toJson(death);
        plugin.publish(payload);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Payload payload = base(event.getPlayer(), Payload.JOIN);
        plugin.publish(payload);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Payload payload = base(event.getPlayer(), Payload.QUIT);
        plugin.publish(payload);
    }

    private Payload base(Player player, String type) {
        Payload p = new Payload();
        p.type = type;
        p.server = plugin.serverId();
        p.uuid = player.getUniqueId().toString();
        p.name = player.getName();
        String nick = plugin.names() == null ? null : plugin.names().nick(player.getName());
        p.nick = nick == null ? "" : nick;
        return p;
    }

    private static String toJson(Component component) {
        return GsonComponentSerializer.gson().serialize(component);
    }
}
