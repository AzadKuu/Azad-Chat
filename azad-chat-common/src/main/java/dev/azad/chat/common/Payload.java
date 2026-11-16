package dev.azad.chat.common;

import java.util.Map;

/** 跨服总线上的消息载体。 */
public final class Payload {

    public static final String CHAT = "chat";
    public static final String DEATH = "death";
    public static final String JOIN = "join";
    public static final String QUIT = "quit";

    public String type = CHAT;
    /** Velocity 侧的服务器 id（oldworld / newworld / minigames / creative / lobby） */
    public String server = "";
    public String uuid = "";
    /** 原始玩家名 */
    public String name = "";
    /** 展示昵称（可能带 & 颜色代码） */
    public String nick = "";
    /** Adventure Component 的 JSON 序列化（聊天正文 / 死亡消息） */
    public String body = "";
    public long ts = System.currentTimeMillis();

    public String toJson() {
        return new Json.Obj()
                .put("v", 1)
                .put("type", type)
                .put("server", server)
                .put("uuid", uuid)
                .put("name", name)
                .put("nick", nick)
                .put("body", body)
                .put("ts", ts)
                .toString();
    }

    public static Payload fromJson(String json) {
        Map<String, String> m = Json.parse(json);
        Payload p = new Payload();
        p.type = m.getOrDefault("type", CHAT);
        p.server = m.getOrDefault("server", "");
        p.uuid = m.getOrDefault("uuid", "");
        p.name = m.getOrDefault("name", "");
        p.nick = m.getOrDefault("nick", "");
        p.body = m.getOrDefault("body", "");
        try {
            p.ts = Long.parseLong(m.getOrDefault("ts", "0"));
        } catch (NumberFormatException ignored) {
            p.ts = 0L;
        }
        return p;
    }
}
