# AQIssue

在游戏里输入 `/iusse`，弹出**原版对话框**，选好分类、填好标题和内容，点「提交」——
一条反馈同时送达 **GitHub Issue、Discord 频道、QQ 群**。

```
/iusse
 └─ 反馈对话框
     ├─ 反馈分类   （下拉选择，来自 config.yml）
     ├─ 标题       （单行输入）
     ├─ 详细内容   （多行输入）
     └─ 提交 ──┬──► GitHub   │ GitHub App 机器人 → 新建 Issue
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
| **GitHub** | GitHub App（机器人身份） | token 一小时自动轮换、**永不过期**，Issue 显示为 `你的App[bot]` 提交；无需人工续期 |
| **Discord** | 频道 Webhook | 不需要机器人常驻在线，复制一个 URL 就能用 |
| **QQ** | OneBot **反向** WebSocket | 插件监听端口，NapCat / go-cqhttp 主动连过来；插件侧不需要装任何 QQ 协议库 |

三个渠道各自独立开关，可只开其中一个，也可以全开。

## 特性

| 能力 | 说明 |
| --- | --- |
| 原生对话框 | 基于 Paper Dialog API，由客户端渲染，服务端零 GUI 开销，也不会和其他 GUI 插件抢界面 |
| 单 jar 跨版本 | 一份产物覆盖 Paper 1.21.7 至最新版（含 26.x） |
| 玩家零门槛 | 玩家不需要任何账号，服务器统一持有一个机器人凭据 |
| 来源可追溯 | 各渠道的消息都会带上玩家名、UUID、分类、服务端版本与提交时间 |
| 单语言文件 | 所有面向玩家的文案都在 `lang.yml`，改文案不用碰代码和 `config.yml` |
| 零第三方依赖 | HTTP 用 `HttpURLConnection`、JSON 自写、WebSocket 服务端自写、JWT 用 JDK 自带 `SHA256withRSA` |

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

## 配置 GitHub App

> [!IMPORTANT]
> 用的是 GitHub **App**（机器人），不是 PAT。区别在于：App 的 token 由插件自己按需申请、
> 一小时自动轮换，**不会像 PAT 那样到期失效**，也不会因为个人离职/换号而中断。

1. 打开 <https://github.com/settings/apps> → **New GitHub App**
2. 名字随意（例如 `AQIssue Bot`），Homepage URL 填仓库地址
3. 把 **Webhook → Active 取消勾选**（插件用不到）
4. **Permissions → Repository permissions → Issues** 设为 **Read and write**，其余保持 No access
5. 点 **Create GitHub App**，记下页面顶部的 **App ID**
6. 页面下方 **Private keys → Generate a private key**，下载得到 `xxx.pem`，
   放到 `plugins/AQIssue/github-app.pem`
7. 左侧 **Install App** → 选择账号 → *Only select repositories* → 勾选目标仓库 → **Install**
8. 安装完成后地址栏形如 `.../settings/installations/12345678`，其中的数字就是 **Installation ID**

填进配置：

```yaml
channels:
  github:
    enabled: true
    auth-type: "app"
    app-id: "1234567"
    installation-id: "12345678"
    private-key-file: "github-app.pem"
```

> [!TIP]
> 私钥支持 PKCS#1（`BEGIN RSA PRIVATE KEY`）与 PKCS#8（`BEGIN PRIVATE KEY`）两种格式，
> 从 GitHub 直接下载的文件原样使用即可，**不需要 `openssl` 转换**。
> 不想把私钥落盘时，也可以删掉 `private-key-file`，把 PEM 内容整个粘贴到 `private-key` 里。

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

采用的是**反向 WebSocket**：插件在服务端开一个 WebSocket 端口，由 NapCat 主动连过来。

**1. 插件侧配置**

```yaml
channels:
  onebot:
    enabled: true
    bind: "127.0.0.1"      # NapCat 与服务器同机时保持 127.0.0.1
    port: 6700
    path: "/onebot"
    access-token: ""        # 与 NapCat 侧保持一致，留空则不校验
    group-ids: [108441415]  # 要发到哪些 QQ 群
    timeout-millis: 8000
```

**2. NapCat 侧配置**

在 NapCat 的 WebUI（默认 <http://localhost:6099>）里进入 **网络配置**，
新建一个 **WebSocket 客户端（反向）**，URL 填：

```
ws://127.0.0.1:6700/onebot
```

Token 与插件里的 `access-token` 保持一致（两边都留空也可以）。

> [!NOTE]
> 不同版本的 NapCat 里这个菜单可能叫「反向 WebSocket」「Websocket Client」等，认准 **客户端 / 反向 / 主动连接插件** 即可。
> 配置保存后插件控制台会打印 `OneBot 客户端已连接：/127.0.0.1:xxxxx`。

> [!WARNING]
> 如果 NapCat 与 Minecraft 服务器**不在同一台机器**上，需要把 `bind` 改成 `0.0.0.0`，
> 并在防火墙放行该端口 —— 此时**务必设置 `access-token`**，否则任何人连上来都能往群里发消息。

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
  github: { enabled: true, auth-type: "app", app-id: "", installation-id: "", private-key-file: "github-app.pem" }
  discord: { enabled: false, webhook-url: "", username: "服务器反馈", embed-color: 5793266 }
  onebot: { enabled: false, bind: "127.0.0.1", port: 6700, path: "/onebot", access-token: "", group-ids: [] }

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
 [正常] GitHub - GitHub App 机器人 | 剩余限额 4998
 [正常] Discord - Webhook 可用
 [异常] QQ 群 - 已监听 127.0.0.1:6700，但暂无客户端连接
```

> [!IMPORTANT]
> `config.yml` 里保存着 GitHub App 私钥路径与会话凭据，请确保 `plugins/AQIssue/`
> 只有服务端进程可读，并且不要把私钥提交到公开仓库。

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
│   └─ OneBotChannel.java          OneBot 反向 WebSocket 服务端
├─ github/
│   ├─ GitHubAppAuth.java          JWT(RS256) → installation token，自动续期
│   ├─ Pem.java                    PKCS#1 / PKCS#8 私钥解析
│   └─ MiniJson.java               极简 JSON 编解码
├─ net/
│   ├─ Http.java                   共用 HTTP 客户端
│   └─ WebSocketConnection.java    手写 WebSocket 服务端连接
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
App 没有安装到该仓库，或安装时没勾选这个仓库。到 App 设置的 Install App 里补上。

**GitHub 渠道报 401？**
App ID、Installation ID 或私钥有一项不对。注意 App ID 是「App 页面顶部」的数字，
Installation ID 是「安装后地址栏里」的数字，两者不同。

**Discord 报 404 / 401？**
Webhook 被删除或 URL 复制不全，重新复制一次。

**QQ 群一直显示「暂无客户端连接」？**
NapCat 的反向 WS 没连上：检查 URL、端口是否放行、两边 token 是否一致；
连接成功时插件控制台会有 `OneBot 客户端已连接` 的日志。

**国内服务器连不上 GitHub / Discord？**
在 `config.yml` 里启用 `github.proxy`。

## 许可

[GPL-3.0](LICENSE)
