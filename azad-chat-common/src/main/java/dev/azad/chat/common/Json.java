package dev.azad.chat.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON 工具：只服务本插件「扁平对象」总线协议，不引入第三方库。
 */
public final class Json {

    private Json() {
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append('\\').append('"');
                case '\\' -> sb.append('\\').append('\\');
                case '\n' -> sb.append('\\').append('n');
                case '\r' -> sb.append('\\').append('r');
                case '\t' -> sb.append('\\').append('t');
                case '\b' -> sb.append('\\').append('b');
                case '\f' -> sb.append('\\').append('f');
                default -> {
                    if (c < 0x20) {
                        sb.append('\\').append('u').append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    /** 扁平对象写入器。 */
    public static final class Obj {

        private final Map<String, String> raw = new LinkedHashMap<>();

        public Obj put(String key, String value) {
            if (value != null) {
                raw.put(key, '"' + escape(value) + '"');
            }
            return this;
        }

        public Obj put(String key, long value) {
            raw.put(key, Long.toString(value));
            return this;
        }

        public Obj put(String key, boolean value) {
            raw.put(key, Boolean.toString(value));
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append('"').append(':').append(e.getValue());
            }
            return sb.append('}').toString();
        }
    }

    /** 解析扁平对象；字符串反转义，数字/布尔按原文保留。 */
    public static Map<String, String> parse(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        int i = skipWs(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') {
            return out;
        }
        i++;
        while (i < json.length()) {
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) == '}') {
                break;
            }
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) != '"') {
                break;
            }
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != ':') {
                break;
            }
            i = skipWs(json, i + 1);
            if (i >= json.length()) {
                break;
            }
            if (json.charAt(i) == '"') {
                StringBuilder val = new StringBuilder();
                i = readString(json, i, val);
                out.put(key.toString(), val.toString());
            } else {
                int start = i;
                while (i < json.length() && ",}".indexOf(json.charAt(i)) < 0) {
                    i++;
                }
                out.put(key.toString(), json.substring(start, i).trim());
            }
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int readString(String s, int i, StringBuilder sb) {
        i++;
        StringBuilder raw = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                raw.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                i++;
                break;
            }
            raw.append(c);
            i++;
        }
        sb.append(unescape(raw.toString()));
        return i;
    }
}
