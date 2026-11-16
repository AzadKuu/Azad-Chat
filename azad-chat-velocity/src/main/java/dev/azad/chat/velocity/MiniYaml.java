package dev.azad.chat.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 读取器：只支持「缩进嵌套的 key: value」与简单的行内 { } 映射取值。
 * 目的是不依赖任何 YAML 库（代理端不保证有 snakeyaml）。
 */
public final class MiniYaml {

    private final Map<String, String> flat = new LinkedHashMap<>();

    public static MiniYaml empty() {
        return new MiniYaml();
    }

    public static MiniYaml load(Path path) throws IOException {
        MiniYaml yaml = new MiniYaml();
        if (path == null || !Files.isRegularFile(path)) {
            return yaml;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Deque<String> stack = new ArrayDeque<>();
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String body = line.substring(indent).trim();
            while (stack.size() * 2 > indent && !stack.isEmpty()) {
                stack.removeLast();
            }
            int colon = indexOfKeyColon(body);
            if (colon <= 0) {
                continue;
            }
            String key = body.substring(0, colon).trim();
            String rest = body.substring(colon + 1).trim();
            if (rest.isEmpty()) {
                stack.addLast(key);
                continue;
            }
            String prefix = stack.isEmpty() ? "" : String.join(".", stack) + ".";
            yaml.flat.put(prefix + key, unquote(rest));
        }
        return yaml;
    }

    public String get(String key, String def) {
        String v = flat.get(key);
        return v == null ? def : v;
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key, Boolean.toString(def)).trim();
        return "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "1".equals(v);
    }

    public static String unquote(String s) {
        if (s == null || s.length() < 2) {
            return s;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int indexOfKeyColon(String body) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && (i == 0 || raw.charAt(i - 1) == ' ')) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }
}
