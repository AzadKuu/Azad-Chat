package dev.azad.chat.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 展示名服务：LuckPerms 前后缀（与 TAB 同源）+ ChineseName 中文昵称（读它的配置文件）。
 */
public final class NameService {

    private final AzadChatPaper plugin;

    private volatile Map<String, String> nicks = Map.of();
    private volatile long stamp = Long.MIN_VALUE;

    private boolean enabled = true;
    private File file;
    private String colorFrom = "&";
    private int maxLength = 16;

    public NameService(AzadChatPaper plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("nickname.enabled", true);
        colorFrom = plugin.getConfig().getString("nickname.color-from", "&");
        maxLength = plugin.getConfig().getInt("nickname.maxlength", 16);
        String path = plugin.getConfig().getString("nickname.file",
                "/home/minecraft/nijijoji/mc01/plugins/ChineseName/config.yml");
        File candidate = new File(path);
        if (!candidate.isAbsolute()) {
            candidate = new File(plugin.getDataFolder(), path);
            if (!candidate.isFile()) {
                candidate = new File(Bukkit.getWorldContainer().getParentFile(), path);
            }
        }
        file = candidate;
        stamp = Long.MIN_VALUE;
        refresh();
    }

    public String describe() {
        if (!enabled) {
            return "已关闭";
        }
        if (file == null || !file.isFile()) {
            return "文件不存在: " + file;
        }
        return file.getPath() + "（" + nicks.size() + " 条）";
    }

    public String nick(String playerName) {
        if (!enabled || playerName == null) {
            return null;
        }
        refresh();
        return nicks.get(playerName.toLowerCase(Locale.ROOT));
    }

    /** 最终展示名：LP 前缀 + （昵称或原名） + LP 后缀。 */
    public String displayName(Player player) {
        String nick = nick(player.getName());
        String base = nick == null ? player.getName() : nick;
        if (base.length() > maxLength) {
            base = base.substring(0, maxLength);
        }
        return lp(player, true) + base + lp(player, false);
    }

    private void refresh() {
        if (!enabled || file == null || !file.isFile()) {
            return;
        }
        long modified = file.lastModified();
        if (modified == stamp) {
            return;
        }
        synchronized (this) {
            if (modified == stamp) {
                return;
            }
            Map<String, String> map = new HashMap<>();
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int idx = line.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        map.put(key.toLowerCase(Locale.ROOT), recolor(value));
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("读取昵称文件失败: " + e);
            }
            nicks = map;
            stamp = modified;
        }
    }

    private String recolor(String s) {
        if (colorFrom != null && !colorFrom.isEmpty() && !"&".equals(colorFrom)) {
            return s.replace(colorFrom, "§");
        }
        return s.replace('&', '§');
    }

    private String lp(Player player, boolean prefix) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return "";
        }
        try {
            var provider = net.luckperms.api.LuckPermsProvider.get();
            var user = provider.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                return "";
            }
            var meta = user.getCachedData().getMetaData();
            String value = prefix ? meta.getPrefix() : meta.getSuffix();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
