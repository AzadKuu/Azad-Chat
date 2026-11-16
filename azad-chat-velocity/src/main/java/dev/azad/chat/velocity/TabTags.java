package dev.azad.chat.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 TAB 的 groups.yml，取 per-server.&lt;服务器&gt;._DEFAULT_.tabprefix 作为「服务器标签」，
 * 保证聊天里的头衔与 TAB 列表里看到的一致（同一份配置源）。
 */
public final class TabTags {

    /** 只接受带引号的值，避免把行尾注释一起吞进来（TAB 生成的配置每行都带 ` # ...` 注释）。 */
    private static final Pattern INLINE_TABPREFIX =
            Pattern.compile("tabprefix\\s*:\\s*(\"[^\"]*\"|'[^']*')");

    private final Map<String, String> templates = new LinkedHashMap<>();
    private final String fallback;

    private TabTags(String fallback) {
        this.fallback = fallback == null ? "" : fallback;
    }

    /** 去掉 YAML 行尾注释（感知引号，避免把 "a#b" 里的 # 当注释）。 */
    static String stripComment(String value) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble
                    && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return value.substring(0, i).trim();
            }
        }
        return value.trim();
    }

    public static TabTags load(Path groupsYml, String fallback) {
        TabTags tags = new TabTags(fallback);
        if (groupsYml == null || !Files.isRegularFile(groupsYml)) {
            return tags;
        }
        try {
            List<String> lines = Files.readAllLines(groupsYml, StandardCharsets.UTF_8);
            boolean inPerServer = false;
            String current = null;
            for (String raw : lines) {
                if (raw.isBlank() || raw.trim().startsWith("#")) {
                    continue;
                }
                int indent = 0;
                while (indent < raw.length() && raw.charAt(indent) == ' ') {
                    indent++;
                }
                String body = raw.trim();
                if (indent == 0) {
                    inPerServer = body.startsWith("per-server:");
                    current = null;
                    continue;
                }
                if (!inPerServer) {
                    continue;
                }
                if (indent == 2) {
                    int colon = body.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    current = MiniYaml.unquote(body.substring(0, colon).trim());
                    String rest = stripComment(body.substring(colon + 1).trim());
                    if (rest.startsWith("{")) {
                        Matcher m = INLINE_TABPREFIX.matcher(rest);
                        if (m.find()) {
                            tags.templates.put(current, MiniYaml.unquote(m.group(1).trim()));
                        }
                    }
                    continue;
                }
                if (current != null && body.startsWith("tabprefix:")) {
                    String value = stripComment(body.substring("tabprefix:".length()).trim());
                    Matcher m = INLINE_TABPREFIX.matcher(value);
                    if (m.find()) {
                        value = m.group(1);
                    }
                    tags.templates.put(current, MiniYaml.unquote(value.trim()));
                }
            }
        } catch (IOException ignored) {
            return tags;
        }
        return tags;
    }

    public String template(String serverId) {
        if (serverId == null) {
            return fallback;
        }
        return templates.getOrDefault(serverId, fallback);
    }

    public Map<String, String> all() {
        return templates;
    }
}
