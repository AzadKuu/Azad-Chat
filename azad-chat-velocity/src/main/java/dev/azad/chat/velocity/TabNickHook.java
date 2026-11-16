package dev.azad.chat.velocity;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * 把子服 ChineseName 里的中文昵称提供给 TAB（代理侧）。
 *
 * <p>TAB 的 Velocity 构建不带 PlaceholderAPI，但自带 {@code me.neznamy.tab.api.TabAPI}。
 * 这里用反射注册一个玩家占位符 {@code %azadchat_nick%}，TAB 的 groups.yml 里
 * {@code customtabname: "%azadchat_nick%"} 就能显示中文名。没有装 TAB 时静默跳过。</p>
 */
final class TabNickHook {

    private static final String PLACEHOLDER = "%azadchat_nick%";

    private TabNickHook() {
    }

    /** @return true = 注册成功；false = TAB 还没准备好或未安装，调用方可稍后重试 */
    static boolean tryInstall(Logger logger, Path nickFile) {
        if (nickFile == null) {
            return false;
        }
        Class<?> api;
        try {
            api = Class.forName("me.neznamy.tab.api.TabAPI");
        } catch (Throwable ignored) {
            logger.info("[Azad-Chat] 未检测到 TAB，跳过 TAB 中文名占位符注册");
            return false;
        }
        try {
            Object instance;
            try {
                instance = api.getMethod("getInstance").invoke(null);
            } catch (Throwable notReady) {
                return false; // TAB 尚未完成 enable，稍后重试
            }
            if (instance == null) {
                return false;
            }
            Object manager = instance.getClass().getMethod("getPlaceholderManager").invoke(instance);

            NickSource source = new NickSource(nickFile);
            Function<Object, String> replacer = player -> {
                try {
                    Object name = player.getClass().getMethod("getName").invoke(player);
                    return source.lookup(name == null ? "" : name.toString());
                } catch (Throwable t) {
                    return "";
                }
            };

            for (Method m : manager.getClass().getMethods()) {
                if (!m.getName().equals("registerPlayerPlaceholder") || m.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types[0] != String.class || types[1] != int.class || types[2] != Function.class) {
                    continue;
                }
                m.setAccessible(true);
                try {
                    m.invoke(manager, PLACEHOLDER, 500, replacer);
                } catch (Throwable invokeFailed) {
                    return false;
                }
                logger.info("[Azad-Chat] 已向 TAB 注册玩家占位符 {}（数据源 {}）", PLACEHOLDER, nickFile);
                return true;
            }
            logger.warn("[Azad-Chat] 没找到可用的 registerPlayerPlaceholder(String,int,Function) 方法");
            return false;
        } catch (Throwable t) {
            logger.warn("[Azad-Chat] 注册 TAB 中文名占位符失败（不影响聊天）", t);
            return false;
        }
    }

    /** 极简 YAML 读取：只认 `玩家名: 昵称` 这种顶层键值，带 mtime 缓存。 */
    private static final class NickSource {

        private final Path file;
        private volatile long stamp = -1L;
        private volatile Map<String, String> cache = Map.of();

        NickSource(Path file) {
            this.file = file;
        }

        String lookup(String playerName) {
            if (playerName.isEmpty()) {
                return "";
            }
            Map<String, String> map = load();
            String nick = map.get(playerName.toLowerCase(Locale.ROOT));
            return nick == null || nick.isBlank() ? playerName : nick;
        }

        private Map<String, String> load() {
            try {
                if (!Files.isRegularFile(file)) {
                    return Map.of();
                }
                long now = Files.getLastModifiedTime(file).toMillis();
                if (now == stamp) {
                    return cache;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                Map<String, String> map = new HashMap<>();
                for (String raw : lines) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                        continue;
                    }
                    int idx = line.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = unquote(line.substring(0, idx).strip());
                    String value = unquote(stripComment(line.substring(idx + 1).strip()));
                    if (!key.isEmpty() && !value.isEmpty()) {
                        map.put(key.toLowerCase(Locale.ROOT), value);
                    }
                }
                cache = map;
                stamp = now;
                return map;
            } catch (IOException e) {
                return cache;
            }
        }

        private static String stripComment(String value) {
            boolean quoted = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\'') {
                    quoted = !quoted;
                } else if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                    return value.substring(0, i).strip();
                }
            }
            return value;
        }

        private static String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }
    }
}
