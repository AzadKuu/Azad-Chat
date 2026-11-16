package dev.azad.chat.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

/**
 * 把「无参 /home」补成「/home home」，交给 HuskHomes 执行（很多模组服的习惯）。
 * 只拦截完全空参的 /home，其它写法一律放行。
 */
public final class HomeAliasListener implements Listener {

    private final AzadChatPaper plugin;
    private final String from;
    private final String to;

    public HomeAliasListener(AzadChatPaper plugin, String from, String to) {
        this.plugin = plugin;
        this.from = from.startsWith("/") ? from.substring(1) : from;
        this.to = to;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return;
        }
        String body = message.startsWith("/") ? message.substring(1) : message;
        body = body.trim();
        // 只处理完全无参的 /home（不处理 /home xxx、/homes 等）
        if (!body.equalsIgnoreCase(from)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        final String target = to.startsWith("/") ? to.substring(1) : to;
        // 下一 tick 执行，避免在事件里重入命令分发
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                player.performCommand(target);
            } catch (Throwable t) {
                plugin.getLogger().warning("[Azad-Chat] home 别名执行失败: " + t);
            }
        });
    }
}
