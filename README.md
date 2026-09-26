# AQIssue

在游戏里输入 `/iusse`，弹出**原版对话框**，选好分类、填好标题和内容，点「提交」——
一条反馈同时送达 **GitHub Issue、Discord 频道、QQ 群**。

```
/iusse
 └─ 反馈对话框
     ├─ 反馈分类   （下拉选择，来自 config.yml）
     ├─ 标题       （单行输入）
     ├─ 详细内容   （多行输入）
     └─ 提交 ──┬──► GitHub   │ PAT → 新建 Issue
               ├──► Discord  │ 频道 Webhook
               └──► QQ 群    │ OneBot 反向 WebSocket
```

投递完成后玩家会看到每个渠道各自的结果：

```
[AQIssue] 提交成功！投递结果：
 ▶ GitHub - Issue #12
 ▶ Discord - 已发送到 Discord
 ▶ QQ 群 - 已发送到 1 个 QQ 群
```

QQ 群里收到的消息（末尾会自动带上刚创建的 Issue 地址）：

```
【Bug 反馈】服务器卡顿
玩家：xcbro
分类：Bug 反馈
服务端：Paper 26.2 (git-Paper-xxx)
时间：2026-09-26 22:15:03
——————
在主城放方块的时候会卡一下，大概持续两秒

https://github.com/MCQingYu-Team/AQ-Iusse/issues/12
```

> [!NOTE]
> 渠道是按 `GitHub → Discord → QQ` 的顺序投递的，所以 Discord 和 QQ 的消息里能带上
> GitHub 刚创建的 Issue 链接（Discord 里还会把这个链接挂在 Embed 标题上）。
> 如果 GitHub 渠道没启用或投递失败，消息末尾会退而显示仓库地址；正文超长时只截断正文，链接必定保留。

## 支持渠道

| 渠道 | 接入方式 | 特点 |
| --- | --- | --- |
| **GitHub** | Fine-grained PAT | 只需给目标仓库勾一个 `Issues: Read and write`，一分钟建好；不用建 App、不用私钥文件 |
| **Discord** | 频道 Webhook | 不需要机器人常驻在线，复制一个 URL 就能用 |
| **QQ** | OneBot（反向 / 正向 WS 均可） | 插件侧不需要装任何 QQ 协议库；既能等 NapCat 连过来，也能由插件主动连出去，断线自动重连 |

三个渠道各自独立开关，可只开其中一个，也可以全开。

## 特性

| 能力 | 说明 |
| --- | --- |
| 原生对话框 | 基于 Paper Dialog API，由客户端渲染，服务端零 GUI 开销，也不会和其他 GUI 插件抢界面 |
| 单 jar 跨版本 | 一份产物覆盖 Paper 1.21.7 至最新版（含 26.x） |
| 玩家零门槛 | 玩家不需要任何账号，服务器统一持有一个凭据 |
| 来源可追溯 | 各渠道的消息都会带上玩家名、UUID、分类、服务端版本与提交时间 |
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
    group-ids: [1102137231]
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
  timeout-millis: 10000
  proxy:                     # 国内服务器连不上 api.github.com / discord.com 时启用
    enabled: false
    host: "127.0.0.1"
    port: 7890

channels:                    # 三个投递渠道，见上文
  github: { enabled: true, token: "" }          # Fine-grained PAT
  discord: { enabled: false, webhook-url: "", username: "服务器反馈", embed-color: 5793266 }
  onebot: { enabled: false, mode: "server", bind: "127.0.0.1", port: 6700, path: "/onebot", url: "", access-token: "", group-ids: [] }

submit:
  cooldown-seconds: 300      # 同一玩家的冷却，0 表示不限制
  min-title-length: 4
  max-title-length: 60
  max-body-length: 800

categories:                  # 对话框里的「反馈分类」下拉项
  - id: "bug"
    name: "Bug 反馈"
    description: "报错、闪退、功能异常、卡顿"
    labels: ["bug"]          # 该分类额外附加的 GitHub 标签
```

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
| `/iusse submit <分类> <标题>\|<内容>` | 一行式提交，任何客户端都能用 | `aqissue.use` |
| `/iusse url` | 显示仓库地址 | `aqissue.use` |
| `/iusse status` | 逐个检查渠道并显示状态 | `aqissue.admin` |
| `/iusse reload` | 重载 `config.yml` 与 `lang.yml`，并重建渠道 | `aqissue.admin` |
| `/iusse help` | 显示帮助 | `aqissue.use` |

别名：`/gitissue`、`/aqissue`。

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
├─ dialog/FeedbackDialog.java      原生对话框构建与回调
├─ channel/
│   ├─ Channel.java                渠道接口
│   ├─ ChannelManager.java         渠道编排与异步分发
│   ├─ ChannelResult.java          单渠道结果
│   ├─ Submission.java             与渠道无关的反馈数据
│   ├─ GitHubChannel.java          GitHub Issue
│   ├─ DiscordChannel.java         Discord Webhook
│   └─ OneBotChannel.java          OneBot（反向 WS 服务端 / 正向 WS 客户端）
├─ github/MiniJson.java            极简 JSON 编解码
├─ net/
│   ├─ Http.java                   共用 HTTP 客户端
│   └─ WebSocketConnection.java    手写 WebSocket 连接（服务端 + 客户端）
├─ config/                         PluginConfig / LangConfig / Category
├─ session/CooldownManager.java    提交冷却
└─ util/Text.java                  颜色代码处理
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
在 `config.yml` 里启用 `github.proxy`。

## 许可

[GPL-3.0](LICENSE)
