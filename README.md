# AQIssue

在游戏里输入 `/iusse`，弹出**原版对话框**，选好分类、填好标题和内容，点「提交」——
一条反馈同时送达 **GitHub Issue、Discord 频道、QQ 群**。

```
/iusse
 └─ ① 选分类          ┌ 每类一个按钮，鼠标悬停显示说明
     │ Bug 反馈  功能建议 │
     │ 举报投诉  其它问题 │        取消
     ↓
    ② 填写
     │ 标题       （单行输入）
     │ 详细内容   （多行输入）
     ↓
    提交 ──┬──► GitHub   │ PAT → 新建 Issue
           ├──► Discord  │ 频道 Webhook
           └──► QQ 群    │ OneBot WebSocket，自动 @ 提交者
```

两个页面都可以按 Esc 关闭，第二步的「返回」能回到上一步改分类。

> [!NOTE]
> 分类超过 8 个时按钮会挤成一团，插件会自动退回「下拉框 + 表单」的单页式。
> 也可以在配置里用 `dialog.two-step: false` 强制单页式。

投递完成后玩家会看到每个渠道各自的结果：

```
[AQIssue] 提交成功！投递结果：
 ▶ GitHub - Issue #12
 ▶ Discord - 已发送到 Discord
 ▶ QQ 群 - 已发送到 1 个 QQ 群
```

QQ 群里收到的消息（自动 @ 提交者，并带上刚创建的 Issue 地址）：

```
【Bug 反馈】[测试服] 服务器卡顿
玩家：xcbro @164907681
QQ：164907681
分类：Bug 反馈
——————
在主城放方块的时候会卡一下，大概持续两秒

https://github.com/MCQingYu-Team/AQ-Iusse/issues/12
```

`@164907681` 在 QQ 里会渲染成蓝色的 `@昵称`，实际拼的是 `[CQ:at,qq=164907681]`。
因为 @ 只显示昵称看不到号码，所以下一行 `QQ：` 仍然保留数字本体。

> [!NOTE]
> 渠道消息默认**不带**「服务端」与「时间」这两行 —— 它们是通知，不是记录：
> QQ 聊天窗口本来就带时间戳，服务端版本在 Issue 的折叠块里展开就有。
> 想加回来就改 `message.show-server` / `message.show-time`。

@ 需要 EasyBot 能查到该玩家绑定的 QQ（见下文「EasyBot 联动」）。
绑定的 QQ 会出现在**所有渠道**里：GitHub Issue 的信息表、Discord 的嵌入正文、QQ 群消息。
只查一次（在投递前），不会每个渠道各查一遍。查不到就不显示，其余内容照常。

> [!NOTE]
> 渠道是按 `GitHub → Discord → QQ` 的顺序投递的，所以 Discord 和 QQ 的消息里能带上
> GitHub 刚创建的 Issue 链接（Discord 里还会把这个链接挂在 Embed 标题上）。
> 如果 GitHub 渠道没启用或投递失败，消息末尾会退而显示仓库地址；正文超长时只截断正文，链接必定保留。

## 支持渠道

| 渠道 | 接入方式 | 特点 |
| --- | --- | --- |
| **GitHub** | Fine-grained PAT | 只需给目标仓库勾一个 `Issues: Read and write`，一分钟建好；不用建 App、不用私钥文件 |
| **Discord** | 频道 Webhook | 不需要机器人常驻在线，复制一个 URL 就能用 |
| **QQ** | OneBot（反向 / 正向 WS 均可） | 插件侧不需要装任何 QQ 协议库；既能等 NapCat 连过来，也能由插件主动连出去；支持发群 + 私聊给指定 QQ，断线自动重连 |

三个渠道各自独立开关，可只开其中一个，也可以全开。

## 特性

| 能力 | 说明 |
| --- | --- |
| 原生对话框 | 基于 Paper Dialog API，由客户端渲染，服务端零 GUI 开销，也不会和其他 GUI 插件抢界面；两步式流程（点分类 → 填内容）比下拉框表单清爽得多 |
| 单 jar 跨版本 | 一份产物覆盖 Paper 1.21.7 至最新版（含 26.x） |
| 玩家零门槛 | 玩家不需要任何账号，服务器统一持有一个凭据 |
| 来源可追溯 | 各渠道的消息都会带上玩家名、UUID、分类、服务端版本与提交时间 |
| 反馈闭环 | Issue 被关闭或有人评论时通知提交者：在线发游戏内消息，离线经 EasyBot 查 QQ 私信；长时间未处理会自动提醒管理员 |
| 玩家可追进度 | `/iusse mine` 看自己提过的反馈，`/iusse reply <编号> <内容>` 直接往 Issue 里补说明，不用重新提一条 |
| 重复检测 | 提交前先在未关闭的反馈里找相似的，命中时弹确认框，同一个问题不会被提十遍 |
| 隐私打码 | 自动给正文里的 IP / 手机号 / 邮箱打码（可按分类单独关闭，举报内容默认不打码）；QQ 号**不打码**，因为插件本就会显示提交者的 QQ |
| 失败重发 | 全部渠道都投递失败时存盘，之后自动重试；补发成功会通知玩家，屡次失败也会告诉他 |
| 游戏内管理 | `/iusse list` / `close` / `priority` / `stats` 让管理员不用切到浏览器就能处理反馈 |
| 优先级 | 用 GitHub 标签实现（`priority: high` 等），玩家提交时可选、管理员事后可改，Issue 列表页能按标签筛选 |
| 一键自检 | `/iusse test` 一次看清 PAT、仓库、标签、各渠道、EasyBot 与重发队列的状态 |
| 额度自保护 | GitHub API 剩余额度偏低时自动降低轮询频率，把额度留给玩家提交 |
| 单语言文件 | 所有面向玩家的文案都在 `lang.yml`，改文案不用碰代码和 `config.yml` |
| 零第三方依赖 | HTTP 用 `HttpURLConnection`、JSON 自写、WebSocket 服务端自写，无任何外部依赖 |

## 兼容性

| 服务端 | 状态 |
| --- | --- |
| Paper 1.21.7 ~ 1.21.11 | 支持 |
| Paper 26.1 / 26.2 / 26.3+ | 支持 |
| Spigot / CraftBukkit | 不支持（Dialog API 是 Paper 独有） |
| Folia | 未测试 |

编译目标是区间下界 **Paper 1.21.7**，并用 `javap` 逐方法比对了 1.21.7 与 26.2 `paper-api` 中
Dialog 相关类的签名，**两者完全一致**，因此没有版本分支代码。

> [!NOTE]
> 对话框是 Minecraft 1.21.6 才加入的客户端功能。如果玩家通过 Geyser（基岩版）或
> ViaVersion 转发上来的低版本客户端进服，对话框不会渲染 —— 这类玩家可以用
> `/iusse submit` 一行式提交，它不依赖客户端界面。

## 安装

1. 从 [Releases](https://github.com/MCQingYu-Team/AQ-Iusse/releases) 下载 `aq-issue-*.jar`，或自行构建。
2. 放进服务端 `plugins/` 目录，**完整重启服务端**（热重载不会注册新插件的命令）。
3. 按下面的说明配置 `plugins/AQIssue/config.yml`。
4. 执行 `/iusse reload`，再用 `/iusse status` 验证三个渠道。

## 配置 GitHub PAT

> [!IMPORTANT]
> 用的是 **Fine-grained personal access token**，只给它目标仓库的 `Issues: Read and write` 权限，
> 不要用 classic token 的 `repo` 全权限。

1. 打开 <https://github.com/settings/personal-access-tokens> → **Fine-grained tokens** → **Generate new token**
2. **Token name** 随意（例如 `AQIssue`），**Expiration** 建议 90 天或自定义
3. **Repository access** 选 *Only select repositories* → 勾上目标仓库
4. **Permissions → Repository permissions → Issues** 设为 **Read and write**
   （`Metadata: Read-only` 会被自动勾上，那是 GitHub 强制项；**Account permissions 一个都不用勾**）
5. 点 **Generate token**，**立刻复制**（离开页面后不再显示）

填进配置：

```yaml
channels:
  github:
    enabled: true
    token: "github_pat_xxxxxxxxxxxx"
```

> [!WARNING]
> `config.yml` 里保存的是**明文 token**，请确保 `plugins/AQIssue/` 只有服务端进程可读，
> 不要把 `config.yml` 提交到公开仓库。Token 过期或泄露时，在 GitHub 上吊销并重新生成。

## 配置 Discord Webhook

1. Discord → 目标频道 → **编辑频道** → **整合 / Integrations** → **Webhook** → **新 Webhook**
2. 起个名字，点 **复制 Webhook URL**
3. 填进配置：

```yaml
channels:
  discord:
    enabled: true
    webhook-url: "https://discord.com/api/webhooks/xxxx/yyyy"
    username: "服务器反馈"
    embed-color: 5793266
```

发送的是一条 Embed：标题为反馈标题，正文为「玩家 / 分类 / 服务端 / 时间 + 内容」。

## 配置 OneBot（QQ）

NapCat 支持两种接法，**先想清楚连接由哪边发起**再选：

| 模式 | 连接方向 | 何时用 |
| --- | --- | --- |
| `server`（默认） | **NapCat → 插件**（反向 WS） | NapCat 能访问到 MC 服务器（两者同机，或 MC 服务器可对外开放端口） |
| `client` | **插件 → NapCat**（正向 WS） | MC 服务器开不了入站端口，而 NapCat 那台有公网 / 端口映射 |

### 模式一：`server`（反向 WS）

插件监听端口，等 NapCat 连过来。

```yaml
channels:
  onebot:
    enabled: true
    mode: "server"
    bind: "127.0.0.1"      # NapCat 与服务器同机用这个；跨机请改 0.0.0.0 并放行端口
    port: 6700
    path: "/onebot"
    access-token: ""        # 与 NapCat 侧保持一致，留空则不校验
    group-ids: [1102137231]  # 要发到哪些 QQ 群
    private-ids: []          # 要私聊给哪些 QQ 号（如管理员），可与群同时发
    timeout-millis: 8000
```

NapCat 侧：WebUI（默认 <http://localhost:6099>）→ **网络配置** → 新建 **WebSocket 客户端（反向）**，
URL 填 `ws://<MC服务器IP>:6700/onebot`，Token 与 `access-token` 一致，创建时勾上「保存时启用」。

连上后插件控制台会打印 `OneBot 客户端已连接：/xxx`。

### 模式二：`client`（正向 WS，插件主动连）

适合「MC 服务器开不了入站端口，但 NapCat 那台有公网端口映射」的场景。

**NapCat 侧**：网络配置 → 新建 **WebSocket 服务端**（不是客户端！），记下它监听的端口（例如 8082），
需要的话填上 token；然后在云厂商控制台把该端口映射到公网（例如 外网 43295 → 内网 8082）。

**插件侧**

```yaml
channels:
  onebot:
    enabled: true
    mode: "client"
    url: "ws://p1.example.com:43295"   # NapCat 那台的对外地址
    access-token: "AQIssue"             # 与 NapCat 侧一致
    group-ids: [1102137231]             # 要发到哪些 QQ 群（可留空）
    private-ids: [10001, 10002]          # 要私聊给哪些 QQ 号（一般是管理员）
    timeout-millis: 8000
```

插件连上后控制台打印 `已连接到 OneBot 服务端 ws://...`；断线每 5 秒自动重连。

> [!NOTE]
> 不同 NapCat 版本里菜单名可能是「WebSocket 服务端」「Websocket Server」等，
> 认准**只填监听端口、不需要填 URL** 的那个就是服务端。
> 目前插件只支持 `ws://`，不支持 `wss://`。

> [!WARNING]
> `mode: client` 且 URL 指向公网时，**`access-token` 是唯一的门禁**，务必用足够长的随机串
> （`openssl rand -hex 16`），别用能被猜到的值 —— 否则任何人都能往你的群里发消息。

## 反馈跟踪与超时提醒

插件会记住每一条提交出去的 Issue，按 `tracking.interval-minutes` 定期查询它的状态。

**状态回传给玩家** —— Issue 被关闭、或有人评论时，当初提交的玩家会在游戏内收到通知：

```
[AQIssue] 你提交的反馈已被处理：服务器卡顿
         https://github.com/MCQingYu-Team/QY-SERVER-IUSSE/issues/12
```

玩家不在线时，插件会按这个顺序尝试：

1. **QQ 私信** —— 如果服务器装了 [EasyBot](https://docs.inectar.cn/docs/easybot/)，通过它的 Bridge 接口查到
   该玩家绑定的 QQ 号，用 OneBot 直接把通知私信给他（关掉 `tracking.notify-qq-offline` 可跳过）
2. **排队等他上线** —— 上线后 3 秒自动补发（关掉 `tracking.queue-offline` 则直接丢弃）

**处理后群内播报** —— Issue 被关闭时，除了通知提交者，还会往渠道（QQ 群 / Discord）发一条：

```
[反馈已处理] #12 服务器卡顿
分类：Bug 反馈
提交者：xcbro
https://github.com/MCQingYu-Team/QY-SERVER-IUSSE/issues/12
```

不想在群里播报的话，把 `tracking.announce-on-close` 改成 `false`。

**超时提醒管理员** —— Issue 超过 `tracking.sla.hours` 小时仍未关闭时，控制台会打印警告，并把提醒**私信给管理员**（默认不发群，避免刷屏）：

```
[AQIssue] [反馈超时] 以下反馈已提交超过 48 小时仍未处理：
 #12 服务器卡顿 (Bug 反馈) 已等待 52 小时
 处理地址：https://github.com/MCQingYu-Team/QY-SERVER-IUSSE/issues/12
```

提醒按 `repeat-hours` 间隔重复，直到 Issue 被关闭。

```yaml
tracking:
  enabled: true
  interval-minutes: 10      # 轮询间隔
  max-per-run: 10           # 每轮最多查几个 Issue（控制 API 用量）
  keep-days: 30             # 只跟踪最近 30 天提交的
  notify-on-close: true
  notify-on-comment: true
  announce-on-close: true  # 处理完成后在渠道里播报一条
  queue-offline: true      # 玩家离线时排队，上线补发
  notify-qq-offline: true  # 玩家离线时经 EasyBot 查 QQ 并私信通知
  sla:
    enabled: true
    hours: 48               # 超过 48 小时算超时
    repeat-hours: 72        # 之后每 72 小时再提醒一次
    broadcast: true         # 除控制台外也发到渠道
    notify-groups: false    # 不往群里发
    notify-private: true    # 只私信 channels.onebot.private-ids
```

> [!NOTE]
> 跟踪记录存在 `plugins/AQIssue/issues.json`，重启后不会重复通知同一条。
> 轮询走 GitHub API，每轮最多 `max-per-run` 次请求 —— 相对 5000/小时的限额可以忽略不计。
> 本段需要 GitHub 渠道可用（有 PAT），否则自动跳过。

## 配置总览

```yaml
language-file: "lang.yml"

github:                      # 仓库信息与 Issue 模板
  api-base: "https://api.github.com"
  owner: "MCQingYu-Team"
  repo: "AQ-Iusse"           # 可以指向任意仓库，例如专门用来收反馈的 QY-SERVER-IUSSE
  labels: ["游戏内反馈"]       # 所有 Issue 都会带上的标签
  title-prefix: "[游戏内] "
  include-player-info: true
  include-server-info: true
  rich-body: true            # Issue 正文用表格 / 折叠块排版
  timeout-millis: 10000
  retry: 2                   # 网络异常 / 429 / 5xx 时的重试次数
  proxy:                     # 国内服务器连不上 api.github.com / discord.com 时启用
    enabled: false
    host: "127.0.0.1"
    port: 7890

channels:                    # 三个投递渠道，见上文
  github: { enabled: true, token: "" }          # Fine-grained PAT
  discord: { enabled: false, webhook-url: "", username: "服务器反馈", embed-color: 5793266 }
  onebot: { enabled: false, mode: "server", bind: "127.0.0.1", port: 6700, path: "/onebot", url: "", access-token: "", group-ids: [], private-ids: [] }

player-qq:                   # 提交者 QQ（所有渠道共用）
  show: true                 # 在 Issue / Discord / QQ 群里显示提交者的 QQ
  mention-in-group: true     # QQ 群里额外 @ 他（拼在「玩家」那一行）

message:                     # 渠道消息（QQ / Discord）里显示哪些行，不影响 Issue
  show-server: false         # 「服务端：xxx」
  show-time: false           # 「时间：xxx」

submit:
  cooldown-seconds: 300      # 同一玩家的冷却，0 表示不限制
  bypass-permission: "aqissue.admin"  # 拥有该权限则豁免冷却与长度校验
  min-title-length: 4
  max-title-length: 60
  max-body-length: 800
  max-reply-length: 300      # /iusse reply 单条回复的长度上限

  duplicate-check: true      # 提交前先找相似反馈，命中时弹确认框
  duplicate-threshold: 0.6   # 判定为疑似重复的相似度阈值（0.1 ~ 1.0）
  mask-sensitive: true       # 自动给 IP / 手机号 / 邮箱打码
  mask-qq: false             # 是否连 QQ 一起打码（默认否，见下文「隐私打码」）

  retry-queue: true          # 投递全部失败时存盘等待重发
  retry-interval-minutes: 10
  retry-max-attempts: 5      # 超过就放弃并告知玩家
  retry-keep-hours: 72

tracking:
  # ……完整项见 config.yml
  rate-limit-threshold: 100  # 剩余额度低于此值就自动降低轮询频率，0 表示不降频

dialog:                      # 对话框外观
  two-step: true             # 先点分类按钮再填内容；false = 下拉框 + 表单单页式
  show-icon: true            # 正文区带一个物品图标

priorities:                  # 优先级（用 GitHub 标签落地，详见下节）
  - id: "low"
    name: "低"
    label: "priority: low"

  - id: "normal"
    name: "普通"
    label: ""               # 留空 = 不加标签 = 没有优先级
    default: true            # 表单默认选中的档位

  - id: "high"
    name: "高"
    label: "priority: high"

  - id: "urgent"
    name: "紧急"
    label: "priority: urgent"

categories:                  # 对话框里的「反馈分类」（建议 ≤ 8 个）
  - id: "bug"
    name: "Bug 反馈"
    description: "报错、闪退、功能异常"
    labels: ["bug"]          # 该分类额外附加的 GitHub 标签

  - id: "crash"
    name: "崩溃卡死"
    description: "服务端或客户端崩溃、卡死不动"
    labels: ["bug", "crash"]

  - id: "lag"
    name: "卡顿掉帧"
    description: "TPS 低、延迟高、画面卡"
    labels: ["performance"]

  - id: "feature"
    name: "功能建议"
    description: "希望服务器新增或优化的内容"
    labels: ["enhancement"]

  - id: "report"
    name: "举报投诉"
    description: "违规行为、玩家纠纷、管理投诉"
    labels: ["report"]
    mask: false              # 举报内容里的 QQ 号是必要证据，不打码

  - id: "appeal"
    name: "封禁申诉"
    description: "对处罚有异议，申请复核"
    labels: ["appeal"]

  - id: "account"
    name: "账号问题"
    description: "登录、绑定、改名、数据丢失"
    labels: ["account"]

  - id: "other"
    name: "其它问题"
    description: "不属于以上分类的内容"
    labels: ["question"]
```

> [!IMPORTANT]
> `labels` 里的标签必须**已经在仓库里创建好**，否则 GitHub 会静默忽略（不报错也不自动建）。
> 用 `/iusse test` 可以查出缺哪些。

## 优先级（Priority）

GitHub 的 Issue 本身**没有内建的 Priority 字段**，所以这里的优先级是用**标签**实现的：
每个档位映射到一个标签，改优先级 = 换标签。好处是在 Issue 列表页可以直接按标签筛选，
而且标签会显眼地显示在标题旁边。

| 档位 | 标签 | 谁选 |
| --- | --- | --- |
| 低 | `priority: low` | 玩家可在提交时选 |
| 普通 | *（不加标签）* | 默认档位 |
| 高 | `priority: high` | 玩家可提交时选，管理员也可事后改 |
| 紧急 | `priority: urgent` | 同上 |

**设计取舍：**

- **默认档位不加标签。** 「普通」留空 `label` 是为了让 Issue 列表保持干净 ——
  只有真正被插队的（`priority: high` / `urgent`）才会被标出来。
  GitHub 页面上没有标签本身也是一种信息：「这条还没被排过序」。
- **优先级不写进 Issue 正文。** 因为管理员随时可以改优先级，写进正文的表格里
  就会变成一条无法自动更新的陈年数据；标签才是唯一可信来源。`/iusse` 在游戏内
  展示时会读本地记录（改优先级时会同步更新）。
- **只有带标签的档位会在 QQ / Discord 消息里显示 `优先级：高`。** 提交「普通」时
  消息里不会多一行噪音。

**玩家怎么设：**

1. 走对话框 —— 第二步表单最下面有一个「优先级」单选；
2. 走一行式指令 —— 在内容末尾加 `!级别`：
   `/iusse submit bug 主城卡顿|放方块时卡一下 !high`。

`!` 开头是为了不和正文里碰巧出现的级别名混淆，写错了就当成正文，不会报错。

**管理员怎么改：**

```
/iusse priority 12 high
```

编号支持 `#12` 写法，级别可以写 id（`high`）也可以写名字（`高`）。
也可以用 Tab 补全。

> [!WARNING]
> GitHub 的 `PUT /issues/{n}/labels` 是**全量覆盖**，直接 PUT 一个只含新优先级的数组
> **会把分类标签一起抹掉**。插件内部是先读出现有标签、摘掉旧的优先级标签、再整体写回，
> 所以分类标签会原样保留。如果你手工用 API 改，也要注意这一点。

> [!TIP]
> 用 `/iusse test` 会一并检查 `priority: low` / `high` / `urgent` 是否已经在仓库里建好。
> 没建的标签 GitHub 会静默忽略（不报错），表现就是「选了紧急但 Issue 上没标签」。

## Issue 长什么样

`github.rich-body: true`（默认）时，一条反馈会变成这样：

> [!NOTE]
> 本条反馈由服务器内 `/iusse` 提交。**直接在本 Issue 下回复即可**，提交的玩家会收到通知。

**反馈信息**

| 项目 | 内容 |
| --- | --- |
| 提交玩家 | **xcbro** |
| 玩家 QQ | `164907681` |
| 反馈分类 | Bug 反馈 `bug` |
| 提交时间 | 2026-09-26 22:15:03 |

**详细内容**

在主城放方块的时候会卡一下，大概持续两秒。

---

<details>
<summary>技术信息（排查时用得上）</summary>

- 服务端：`Leaf 26.2.build.64-alpha`
- 玩家 UUID：`7c9b1f42-...`
- 来源：服务器内 `/iusse`

</details>

几个刻意的选择：

- **顶上那句提示**告诉维护者「直接回复就行了，玩家会收到通知」—— 否则他们会另跑去 QQ 群里回
- **技术信息收进折叠块**：排查时真正要读的只有正文，UUID 与服务端版本属于「需要时才展开」
- **只公开 QQ，不公开其它**：QQ 是联系玩家的最直接方式，所以写在表里；
  IP / 手机号 / 邮箱照旧打码，UUID 收在折叠块里
  （`player-qq.show: false` 可以让 QQ 也不出现）

想回到早期那种项目符号列表就设 `github.rich-body: false`。

## 反馈管理与运维

### 一键自检：`/iusse test`

排查投递问题时先用它，比 `status` 多三件事：把**未启用的渠道也列出来并说明原因**、
检查配置里的标签在仓库里**是否真的存在**、报告 EasyBot 与重发队列状态。

```
[AQIssue] AQIssue 自检结果：
 [正常] GitHub - PAT 有效｜仓库 MCQingYu-Team/QY-SERVER-IUSSE｜剩余额度 4987
 [注意] 缺失标签 - 游戏内反馈、report（GitHub 会静默忽略未创建的标签）
 [注意] Discord - 未启用：配置中未启用
 [异常] QQ 群 - 未连接（正在重试 ws://p1.i9mr.com:44987/）
 [正常] EasyBot - 已加载，可用 /iusse qq <玩家> 查询绑定的 QQ
 [正常] 反馈跟踪 - 已记录 12 条｜待重发 0 条
```

> [!TIP]
> 「标签不存在」是个很容易踩的坑 —— GitHub 对未知标签**不报错也不创建**，
> 配了等于没配，只有自检才会告诉你。

### 查询玩家绑定的 QQ：`/iusse qq <玩家>`

排查 QQ 通知链路时最有用的一条指令，一次看清「EasyBot 在不在 → 玩家绑没绑 → OneBot 发得出去吗」：

```
/iusse qq xcbro
[AQIssue] 玩家 xcbro 绑定的 QQ：164907681

/iusse qq xcbro 这是一条测试消息
[AQIssue] 测试私信已发送到 164907681，去 QQ 上看看收到没有。
```

第二个参数是可选的：填了就会往那个 QQ 发一条私信，用来验证 OneBot 通路。
输入玩家名时支持 Tab 补全（提示当前在线玩家）。

### 在群里 @ 玩家：`/iusse at <玩家> [消息]`

反馈投递到 QQ 群时会自动 @ 提交者（见上文「支持渠道」的群消息示例）。
这条指令用来**单独验证 @ 链路** —— 它走的是和真实反馈完全一样的路径
（同一个 EasyBot 查询、同一个渠道发送），只是内容换成一句测试消息：

```
/iusse at xcbro
[AQIssue] 已在 QQ 群里 @ 164907681，去群里看看收到没有。

/iusse at xcbro 你自己来看看这条反馈
```

群消息实际发出的是（@ 拼在「玩家」那一行，和真实反馈完全一致，所以能当预览用）：

```
【AQIssue 测试】
玩家：xcbro [CQ:at,qq=164907681]
消息：你自己来看看这条反馈
```

> [!NOTE]
> @ 依赖 EasyBot 查到该玩家绑定的 QQ。没绑定、EasyBot 不可用、或
> `channels.onebot.mention-player: false` 时都不会 @，消息其余部分照常发出。
> CQ 码只发给 OneBot 渠道，不会漏进 Discord。

### 在游戏里处理反馈

```
/iusse list                    # 待处理的按等待时间排序，超时的标红加粗
/iusse close 12 已在 v2.4.1 修复   # 理由会作为 Issue 评论发出，并通知提交者
/iusse stats                   # 提交量 / 待处理 / 超时 / 平均处理时长 / 待重发
```

`/iusse stats` 完全基于本地的 `issues.json` 计算，**不消耗任何 API 额度**。

### 隐私打码

反馈正文最终会落到公开的 GitHub 仓库与 QQ 群里，而玩家经常顺手把「我的 IP 是 1.2.3.4」
一起写进去。插件在投递前会把这几类信息打码：

| 类型 | 处理方式 |
| --- | --- |
| IPv4 | `1.2.3.4` → `1.2.*.*`（每段必须 ≤ 255，避免误伤版本号） |
| 手机号 | `13812345678` → `138****5678` |
| 邮箱 | `zhangsan@qq.com` → `z***@qq.com` |

只处理高置信度的形态，宁可漏掉也不误伤。
判到就打码并提示玩家 `（检测到疑似 IP / 手机号 / 邮箱，已自动打码）`。

> [!NOTE]
> **QQ 号默认不打码。** 插件本来就会把提交者绑定的 QQ 显示在所有渠道里
> （Issue 信息表 / Discord / QQ 群），正文里再把它涂掉就自相矛盾了。
> 想彻底不让 QQ 出现，设 `submit.mask-qq: true` 并把 `player-qq.show` 改成 `false`。

举报投诉需要保留原始内容（里面的玩家名、QQ 号往往是证据），
所以那个分类用 `mask: false` 单独关掉了；想全局关掉就设 `submit.mask-sensitive: false`。

### 重复检测

开启 `submit.duplicate-check` 后，提交前会先把标题和最近未关闭的反馈逐一比对：

- 先归一化（去掉空格、标点，统一小写），再用字符二元组的 Dice 系数算相似度
- 短标题互相包含也算重复（例如「传送门附近崩溃」与「下界传送门附近崩溃」）
- 超过 `duplicate-threshold`（默认 0.6）就弹一个确认框：

```
┌─ 可能已有相同反馈 ─────────────┐
│ 已经有人提过相似的问题：          │
│ #12 传送门附近必崩               │
│ https://github.com/.../issues/12  │
│            [仍然提交] [返回]      │
└────────────────────────────────┘
```

只跟**未关闭**的反馈比：已经处理完的问题再提一次，通常是真碰到了新问题。
列表带 5 分钟缓存，不会每提交一次就打一次 API。

### 投递失败自动重发

之前所有渠道都失败时，玩家看到「投递失败」就没了后续 —— 而失败的原因往往是
服务器到 GitHub 的网络抖了一下（国内很常见），过几分钟自己就好了。

现在失败的反馈会存到 `plugins/AQIssue/queue.json`，每 `retry-interval-minutes` 分钟重试一次：

- 重试成功 → 补建 Issue，并告诉玩家「之前那条已补发成功」+ 链接
- 重试 `retry-max-attempts` 次仍失败，或超过 `retry-keep-hours` 小时 → 放弃，并告知玩家可以重新提交
- 队列上限 200 条，单轮最多重试 5 条（不把 API 额度一次打光）

### API 额度自保护

GitHub 未认证限额只有 60/小时，认证后是 5000/小时。跟踪轮询会记录每次响应的
`X-RateLimit-Remaining`，低于 `tracking.rate-limit-threshold`（默认 100）时
自动把轮询间隔临时放大 6 倍，把额度留给「玩家提交反馈」这件更要紧的事，额度恢复后自动还原。

### lang.yml

面向玩家的全部文本都在这个文件里。想换文案只改它；想加别的语言就复制一份成 `lang_en.yml`，
再把 `config.yml` 的 `language-file` 指过去。

- 聊天栏消息支持 `&` 颜色代码
- 对话框文字由原版界面渲染，写纯文本即可，`&` 会被自动剥掉
- `{xxx}` 是运行时占位符，不要删

## 指令

| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `/iusse` | 打开反馈对话框 | `aqissue.use`（默认所有玩家） |
| `/iusse submit <分类> <标题>\|<内容> [!级别]` | 一行式提交，任何客户端都能用；末尾 `!high` 可指定优先级 | `aqissue.use` |
| `/iusse mine` | 查看自己提交过的反馈与处理进度 | `aqissue.use` |
| `/iusse reply <编号> <内容>` | 往自己的反馈里补说明，直接变成 Issue 评论 | `aqissue.use` |
| `/iusse url` | 显示仓库地址 | `aqissue.use` |
| `/iusse list [open\|closed\|all]` | 列出反馈，等得最久的最先显示 | `aqissue.admin` |
| `/iusse close <编号> [理由]` | 关闭 Issue（理由会作为评论发出）并通知提交者 | `aqissue.admin` |
| `/iusse priority <编号> <级别>` | 改反馈优先级（换 GitHub 标签，分类标签会保留） | `aqissue.admin` |
| `/iusse stats` | 提交量 / 待处理 / 超时 / 平均处理时长 / 待重发 | `aqissue.admin` |
| `/iusse test` | 逐项自检 GitHub、标签、各渠道、EasyBot 与队列 | `aqissue.admin` |
| `/iusse qq <玩家> [测试消息]` | 查玩家绑定的 QQ，可顺手发一条测试私信 | `aqissue.admin` |
| `/iusse at <玩家> [消息]` | 在 QQ 群里 @ 该玩家（验证 @ 链路） | `aqissue.admin` |
| `/iusse status` | 逐个检查已启用渠道 | `aqissue.admin` |
| `/iusse reload` | 重载 `config.yml` 与 `lang.yml`，并重建渠道 | `aqissue.admin` |
| `/iusse help` | 显示帮助 | `aqissue.use` |

别名：`/gitissue`、`/aqissue`。

> [!TIP]
> 拥有 `aqissue.admin`（默认 OP 都有）的玩家**不受提交限制** —— 冷却直接跳过，标题与正文的长度校验也放行，
> 方便管理员测试或代提反馈。改 `submit.bypass-permission` 可以换成别的权限节点，留空则所有人一视同仁。
> 注意「标题 / 内容不能为空」这条对所有人都生效，不会豁免。

`/iusse status` 输出示例：

```
[AQIssue] 投递渠道状态：
 [正常] GitHub - PAT 认证通过 | 剩余限额 4998
 [正常] Discord - Webhook 可用
 [异常] QQ 群 - 已监听 127.0.0.1:6700，但暂无客户端连接
```

> [!IMPORTANT]
> `config.yml` 里保存着明文 PAT，请确保 `plugins/AQIssue/` 只有服务端进程可读，
> 并且不要把 `config.yml` 提交到公开仓库。

## 构建

需要 **JDK 21+** 与 Maven：

```bash
mvn clean package
# 产物：target/aq-issue-1.0.0.jar
```

推送到 `main` 或打 `v*` 标签会触发 GitHub Actions 自动构建；打标签时产物会附加到对应的 Release。

## 项目结构

```
src/main/java/cn/aqcraft/iusse/
├─ AqIssuePlugin.java              插件主类：指令注册、投递编排、结果汇总
├─ command/IssueCommand.java       /iusse 指令与 Tab 补全
├─ dialog/FeedbackDialog.java      原生对话框（提交 / 重复确认）
├─ channel/
│   ├─ Channel.java                渠道接口
│   ├─ ChannelManager.java         渠道编排与异步分发
│   ├─ ChannelResult.java          单渠道结果
│   ├─ Submission.java             与渠道无关的反馈数据
│   ├─ GitHubChannel.java          GitHub Issue
│   ├─ DiscordChannel.java         Discord Webhook
│   └─ OneBotChannel.java          OneBot（反向 WS 服务端 / 正向 WS 客户端）
├─ github/
│   ├─ GitHubApi.java              共用 REST 客户端（重试、额度、错误翻译）
│   └─ MiniJson.java               极简 JSON 编解码
├─ net/
│   ├─ Http.java                   共用 HTTP 客户端（PATCH 走 JDK HttpClient）
│   └─ WebSocketConnection.java    手写 WebSocket 连接（服务端 + 客户端）
├─ config/                         PluginConfig / LangConfig / Category
├─ tracking/
│   ├─ IssueRecord.java            单条跟踪记录
│   ├─ IssueTracker.java           Issue 状态轮询、回传玩家、超时提醒
│   ├─ IssueStats.java             统计（/iusse stats）
│   ├─ QueuedFeedback.java         一条待重发的反馈
│   └─ FeedbackQueue.java          投递失败重发队列
├─ integration/EasyBotBridge.java  反射查询玩家绑定的 QQ（无编译期依赖）
├─ session/CooldownManager.java    提交冷却
├─ listener/PlayerJoinListener.java 上线补发离线通知
└─ util/
    ├─ Text.java                   颜色代码处理
    ├─ Sanitizer.java              隐私信息打码
    └─ Similarity.java             标题相似度（重复检测）
```

投递全程在异步线程执行，结果回到主线程再发消息，不会卡服；三个渠道按顺序投递，
其中一个失败不影响其余渠道。

## 常见问题

**`/iusse` 显示未知指令？**
新装插件必须**完整重启服务端**，`/reload` 与 PlugManX 不会注册新命令。
若重启后仍如此，检查日志里有没有 `[AQIssue] 指令注册成功` 这一行。

**GitHub 渠道报 404？**
PAT 没有被授权访问该仓库 —— 到 token 设置里的 Repository access 把仓库勾上。

**GitHub 渠道报 401？**
PAT 无效或已过期，重新生成一个并更新 `channels.github.token`。

**GitHub 渠道报 403？**
PAT 的 Issues 权限给成了 `Read-only`，改成 `Read and write`；也可能是 API 限额用尽。

**Discord 报 404 / 401？**
Webhook 被删除或 URL 复制不全，重新复制一次。

**QQ 群一直显示「暂无客户端连接」？**
NapCat 的反向 WS 没连上：检查 URL、端口是否放行、两边 token 是否一致；
连接成功时插件控制台会有 `OneBot 客户端已连接` 的日志。

**国内服务器连不上 GitHub / Discord？**
在 `config.yml` 里启用 `github.proxy`。偶尔抖动不用管，插件会自动重试（`github.retry`），
重试仍失败的话反馈会进重发队列，不会丢。

**玩家说反馈提了但仓库里没有？**
执行 `/iusse stats` 看「待重发队列」 —— 有条目就说明当时投递失败、正在自动重试；
再看控制台有没有对应的警告。如果队列为 0 而仓库确实没有，检查是不是被 PAT 权限挡住了
（用 `/iusse test` 一眼就能看出）。

**担心玩家把 QQ 号写进公开仓库？**
默认已开启自动打码（`submit.mask-sensitive`），举报投诉分类因为需要保留证据而单独关掉了。

**同一个问题被提了好几遍？**
`submit.duplicate-check` 默认开启，命中时会在提交前弹确认框。
觉得太敏感就把 `duplicate-threshold` 调高（比如 0.75），嫌不灵就调低。

## 许可

[GPL-3.0](LICENSE)
