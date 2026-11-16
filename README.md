# Azad-Chat

跨服聊天 / 死亡消息 / 进服退服 广播插件（Velocity 代理 + Paper 子服，Redis 总线）。

目标是替代 TrChat 里「跨服同步」那部分能力：**只需要全服都能看到聊天与死亡消息，并且头衔与 TAB 里看到的一致**，不需要 TrChat 的其它功能。

---

## 为什么是新写而不是改 TrChat

- TrChat 的代理端配置本身就缺 `settings.yml` / `channels/`，而子服的 `Global.yml` 又是 `Proxy/Force-Proxy/Double-Transfer` 全开，重复转发的问题靠改配置难以稳定收敛；
- 死亡消息、头衔一致性、中文昵称这三件事都要另外写扩展，TrChat 只处理聊天；
- TrChat 基于 TabooLib，编译链重、上游每次跨版本都容易碎，改造成本远高于重写一个只做「广播」的插件。

所以本仓库是**两个独立插件**：

| 模块 | 产物 | 装在哪 |
|---|---|---|
| `azad-chat-common` | 不是插件，被下面两个模块 shade 进去 | — |
| `azad-chat-velocity` | `Azad-Chat-Velocity-1.0.0.jar` | Velocity 代理 `plugins/` |
| `azad-chat-paper` | `Azad-Chat-Paper-1.0.0.jar` | 每个 Paper/Purpur 子服 `plugins/` |

---

## 架构

```
        ┌─────────────────────────── Velocity ───────────────────────────┐
玩家聊天 │  PlayerChatEvent ──► 发布 chat ──► Redis ──► 订阅 ──► 统一渲染 │ ──► 广播给所有在线玩家
        │                                              ▲                │     （发送者所在服不重复发）
        └──────────────────────────────────────────────┼────────────────┘
                                                       │
   ┌───────────────┬───────────────┬───────────────┬───┴───────────┐
   │ oldworld 25566│ newworld 25567│ minigames 25568│ creative 25569│ lobby 25570
   │  Paper 插件：采集 AsyncChatEvent / PlayerDeathEvent / Join / Quit ──► 发布
   └───────────────┴───────────────┴───────────────┴───────────────┘
```

设计要点：

1. **子服只做采集**：把事件打成 JSON 推到 Redis，渲染全部在代理做，避免每台服各写一套格式。
2. **发送者去重**：子服侧把聊天事件注入成「只给发送者看」（`render.mirror-inject: true`），跨服广播交给代理；代理再跳过「发送者 + 原服」这一个接收者，所以不会出现两条。
3. **头衔与 TAB 同源**：代理直接读 `plugins/tab/groups.yml` 的 `per-server.<服>._DEFAULT_.tabprefix`，不另做一份服务器名配置。
4. **昵称在子服解析**：中文昵称来自 `ChineseName` 的配置（`玩家名: §颜色昵称`），子服解析后随消息带上去，代理不必再读文件。
5. **零外部依赖**：Redis 客户端、YAML 读取都是几十行手写实现，不引 Jedis / snakeyaml，避免和代理自带库冲突。

---

## 构建

需要 **JDK 21+**（Velocity 4 的 API 是 Java 25 字节码，若编译期报 `class file major version` 之类的错，就必须用 JDK 25 构建）。

```bash
mvn -B -DskipTests package
# 产物：
#   azad-chat-velocity/target/Azad-Chat-Velocity-1.0.0.jar
#   azad-chat-paper/target/Azad-Chat-Paper-1.0.0.jar
```

依赖坐标（已核对可用）：

- `io.papermc.paper:paper-api:26.3.build.253-stable`
- `com.velocitypowered:velocity-api:4.3.1`
- `net.luckperms:api:5.5`（`provided`，运行期软依赖）

---

## 安装

### 1. 代理（Velocity）

```bash
cp Azad-Chat-Velocity-1.0.0.jar <velocity>/plugins/
# 首次启动会自动生成 <velocity>/plugins/azad-chat/config.yml
```

关键配置（`plugins/azad-chat/config.yml`）：

```yaml
redis:
  host: 127.0.0.1
  port: 6379
  password: ""             # 留空则不 AUTH；有密码时填在这里
  channel: azad-chat

tab:
  groups-yml: "../tab/groups.yml"   # 相对本插件数据目录，指向 TAB 的 groups.yml
```

### 2. 子服（Paper / Purpur）

```bash
cp Azad-Chat-Paper-1.0.0.jar <server>/plugins/
```

关键配置（`plugins/Azad-Chat/config.yml`）：

```yaml
nickname:
  file: "/home/minecraft/nijijoji/mc01/plugins/ChineseName/config.yml"  # 全服共用一份
server:
  id: auto            # 按端口自动识别，或用 port-map 改
```

### 3. 验证

```
/azadchat status      # 子服：看 server-id / 频道 / 昵称文件命中条数
```

代理控制台应出现：

```
[Azad-Chat] 已订阅 Redis 频道 azad-chat
```

---

## 配置项

### 代理端 `plugins/azad-chat/config.yml`

| 键 | 说明 |
|---|---|
| `redis.host/port/password/channel` | Redis 连接与频道，必须与子服一致 |
| `tab.groups-yml` | TAB 的 `groups.yml`，取 `per-server.<服>._DEFAULT_.tabprefix` |
| `tab.fallback-tag` | 读不到时用的兜底服务器标签 |
| `render.chat-format` | 聊天格式，支持 `%name%`（服务器标签+LP 前缀+昵称+LP 后缀）、`%message%` |
| `render.death-format` | 死亡消息格式，支持 `%server%`、`%message%` |
| `render.join-format` / `quit-format` | 进服退服格式，支持 `%server%`、`%player%` |
| `render.log-to-console` | 是否同时打到代理控制台 |
| `nickname-maxlength` | 昵称最多保留多少字符（含颜色代码） |

### 子服 `plugins/Azad-Chat/config.yml`

| 键 | 说明 |
|---|---|
| `redis.*` | 与代理一致 |
| `server.id` | `auto` 或写死 `oldworld` / `newworld` / `minigames` / `creative` / `lobby` |
| `server.port-map` | `auto` 模式下的端口 → 服务器 id 映射 |
| `nickname.enabled` | 是否启用中文昵称 |
| `nickname.file` | ChineseName 的配置文件路径（绝对路径最稳） |
| `nickname.color-from` | 昵称里的颜色前缀，默认 `&`，读入时转 `§` |
| `nickname.maxlength` | 昵称截断长度 |
| `render.mirror-inject` | `true` = 子服只把聊天发给发送者，跨服广播交给代理（推荐） |
| `render.show-to-console` | 保留子服控制台输出 |

---

## 消息协议（Redis 频道 `azad-chat`）

单条 JSON，扁平结构：

```json
{
  "v": 1,
  "type": "chat|death|join|quit",
  "server": "oldworld",
  "uuid": "玩家 UUID",
  "name": "原始玩家名",
  "nick": "§4帅帅+",
  "body": "Adventure Component 的 JSON（聊天正文或死亡消息）",
  "ts": 1700000000000
}
```

`body` 用 Adventure 的 `GsonComponentSerializer` 序列化，死亡消息因此能保留「谁被什么打死」的完整结构，
客户端会本地化（英文客户端看到英文，中文客户端看到中文）。

---

## 已知限制 / TODO

- 代理侧头衔目前取 LuckPerms 的**用户级** prefix/suffix；如果某服的展示依赖组级继承缓存，代理侧可能需要等 LuckPerms 加载完（已有 `loadUser` 兜底）。
- `TAB` 若把 `groups.yml` 放在别处，改 `tab.groups-yml` 即可；读不到时 `TAB 服务器标签={}`，聊天仍可用但没服务器标签。
- 中文昵称是**读文件**而不是走 API，`ChineseName` 改完文件后需要玩家重进（或等 `nickname.maxlength` 那次读取刷新，最多 1 秒）。
- 尚未实现：私聊 `/msg` 的跨服、跨服 `/tell`、聊天频道切换、屏蔽某人消息。

---

## 目录

```
azad-chat-common/    JSON / Payload / RedisBus（被 shade）
azad-chat-paper/     子服采集端
azad-chat-velocity/  代理渲染端
```
