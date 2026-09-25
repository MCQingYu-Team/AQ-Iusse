# AQIssue

在游戏里输入 `/iusse`，弹出**原版对话框**，选好分类、填好标题和内容，点「提交」——
一条 Issue 就出现在你的 GitHub 仓库里。

```
/iusse
 └─ 反馈对话框
     ├─ 反馈分类   （下拉选择，来自 config.yml）
     ├─ 标题       （单行输入）
     ├─ 详细内容   （多行输入）
     ├─ 提交 ─────► GitHub REST API ─────► 仓库新增 Issue
     └─ 取消
```

## 特性

| 能力 | 说明 |
| --- | --- |
| 原生对话框 | 基于 Paper 的 Dialog API，由客户端渲染，服务端零 GUI 开销，也不会和其他 GUI 插件抢界面 |
| 单 jar 跨版本 | 一份产物覆盖 Paper 1.21.7 至最新版（含 26.x），无需为不同服务端打包 |
| 玩家零门槛 | 玩家不需要注册 / 登录 GitHub，服务器统一持有一个 Token 提交 |
| 来源可追溯 | Issue 正文自动附带玩家名、UUID、所选分类、服务端版本与提交时间 |
| 单语言文件 | 所有面向玩家的文案都在 `lang.yml`，改文案不用碰代码和 `config.yml` |
| 无第三方依赖 | 只用 JDK 自带的 `HttpURLConnection` 与自写 JSON 编解码，jar 体积极小 |

## 兼容性

| 服务端 | 状态 |
| --- | --- |
| Paper 1.21.7 ~ 1.21.11 | 支持 |
| Paper 26.1 / 26.2 / 26.3+ | 支持 |
| Spigot / CraftBukkit | 不支持（Dialog API 是 Paper 独有） |
| Folia | 未测试 |

这样打包的依据：项目的编译目标是区间下界 **Paper 1.21.7**，并用 `javap` 逐方法比对了 1.21.7 与
26.2 `paper-api` 中全部 Dialog 相关类（`Dialog`、`DialogBase`、`DialogRegistryEntry.Builder`、
`DialogType`、`DialogInput`、`DialogBody`、`DialogAction`、`DialogResponseView`、
`RegistryBuilderFactory` 等）的签名，**两者完全一致**，因此不存在版本分支代码。

> [!NOTE]
> 在 1.21.7 以上、我们没有实测过的服务端版本上运行时，如果 Dialog API 再次发生破坏性变更，
> 插件会捕获异常并提示玩家改用 `/iusse submit` 一行式提交，不会把报错刷进控制台。

## 安装

1. 从 [Releases](https://github.com/MCQingYu-Team/AQ-Iusse/releases) 下载 `aq-issue-*.jar`，
   或自行构建（见下文）。
2. 丢进服务端的 `plugins/` 目录，重启服务端。
3. 编辑 `plugins/AQIssue/config.yml`，至少填写 `github.owner`、`github.repo` 与 `github.token`。
4. 执行 `/iusse reload`。

## 配置

### config.yml

```yaml
language-file: "lang.yml"        # 语言文件，可换成 lang_en.yml 等

github:
  api-base: "https://api.github.com"
  owner: "MCQingYu-Team"         # 仓库所属者
  repo: "AQ-Iusse"
  token: ""                      # 访问令牌，见下文
  labels: ["游戏内反馈"]           # 所有 Issue 都会带上的标签
  title-prefix: "[游戏内] "       # 标题前缀，便于在仓库里区分来源
  include-player-info: true      # 正文附上玩家名 / UUID
  include-server-info: true      # 正文附上服务端版本
  timeout-millis: 10000
  proxy:
    enabled: false               # 国内服务器连不上 api.github.com 时打开
    host: "127.0.0.1"
    port: 7890

submit:
  cooldown-seconds: 300          # 同一玩家的提交冷却，0 表示不限制
  min-title-length: 4
  max-title-length: 60           # 同时作为对话框输入框的长度上限
  max-body-length: 800

categories:                      # 对话框里的「反馈分类」下拉项
  - id: "bug"
    name: "Bug 反馈"
    description: "报错、闪退、功能异常、卡顿"
    labels: ["bug"]              # 该分类额外附加的 GitHub 标签
```

### lang.yml

面向玩家的全部文本都在这个文件里。想换文案只改它；想加别的语言就复制一份成 `lang_en.yml`，
再把 `config.yml` 的 `language-file` 指过去。文件缺失或缺少某个键时会自动回退到 jar 内置版本。

```yaml
dialog:
  title: "提交反馈"
  confirm: "提交"
  cancel: "取消"
  # ...

submit:
  success: "&a提交成功！Issue #{number}\n&7{url}"
  # ...
```

- 聊天栏消息支持 `&` 颜色代码（如 `&c` 红色）
- 对话框文字由原版界面渲染，写纯文本即可，`&` 会被自动剥掉
- `{xxx}` 是运行时占位符，不要删

## 指令

| 指令 | 说明 | 权限 |
| --- | --- | --- |
| `/iusse` | 打开反馈对话框 | `aqissue.use`（默认所有玩家） |
| `/iusse submit <分类> <标题>\|<内容>` | 一行式提交，适合快捷键或脚本 | `aqissue.use` |
| `/iusse url` | 显示仓库地址 | `aqissue.use` |
| `/iusse status` | 检查仓库连通性、Token 有效性与剩余限额 | `aqissue.admin` |
| `/iusse reload` | 重载 `config.yml` 与 `lang.yml` | `aqissue.admin` |
| `/iusse help` | 显示帮助 | `aqissue.use` |

别名：`/issue`、`/gitissue`、`/aqissue`。

## 创建 GitHub Token

> [!IMPORTANT]
> 推荐使用 **Fine-grained personal access token**，只授予目标仓库的 `Issues: Read and write` 权限，
> 不要用 classic token 的 `repo` 全权限。

1. 打开 GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens**
2. Repository access 选择 *Only select repositories* → 勾选目标仓库
3. Permissions → Repository permissions → **Issues** 设为 *Read and write*
4. 生成后把 token 填入 `config.yml` 的 `github.token`

> [!WARNING]
> `config.yml` 里保存的是明文 token。请确保 `plugins/AQIssue/` 目录只有服务端进程可读，
> 并且不要把 `config.yml` 提交到公开仓库。Token 泄露时立即在 GitHub 上吊销。

## 构建

需要 **JDK 21+** 与 Maven：

```bash
mvn clean package
# 产物：target/aq-issue-1.0.0.jar
```

推送到 `main` 分支或打 `v*` 标签会触发 GitHub Actions 自动构建；
打标签时产物会自动附加到对应的 Release。

## 项目结构

```
src/main/java/cn/aqcraft/iusse/
├─ AqIssuePlugin.java          插件主类：指令注册、提交编排、结果反馈
├─ command/IssueCommand.java   /iusse 指令与 Tab 补全
├─ dialog/FeedbackDialog.java  原生对话框构建与回调
├─ config/
│   ├─ PluginConfig.java       功能配置
│   ├─ LangConfig.java         语言文件
│   └─ Category.java           反馈分类
├─ github/
│   ├─ GitHubClient.java       REST 调用（HttpURLConnection）
│   ├─ GitHubResult.java       创建 Issue 的结果
│   ├─ GitHubStatus.java       连通性检查结果
│   └─ MiniJson.java           极简 JSON 编解码（零依赖）
├─ session/CooldownManager.java 提交冷却
└─ util/Text.java              颜色代码处理
```

提交过程是异步的：HTTP 请求在线程池执行，回调再切回主线程发消息，不会卡服。

## 常见问题

**玩家点了提交但没反应？**
先用 `/iusse status` 看连通性。若提示 TLS 握手失败或超时，说明服务器到 `api.github.com`
的网络不通，在 `config.yml` 里启用 `github.proxy` 指向本地代理。

**Issue 里没有标签？**
GitHub 会静默忽略仓库中不存在的标签。请先在仓库里创建 `游戏内反馈`、`bug` 等标签。

**能提交到私有仓库吗？**
可以，但必须配置 token。

**为什么不能在 Spigot 上用？**
对话框用的是 Paper 独有的 Dialog API，Spigot 没有对应实现。

## 许可

[GPL-3.0](LICENSE)
