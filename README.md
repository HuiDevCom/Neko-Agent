<p align="center"><img src="src/main/resources/icon.png" width="128" alt="Neko Agent"></p>

<p align="center"><a href="README.en.md">English</a> | <a href="README.md">中文</a></p>

# Neko Agent

基于 OpenAI 兼容 API 的 Minecraft Paper 服务端 AI 助手插件。支持 DeepSeek、OpenAI 等任意兼容接口，玩家可通过聊天或命令与 Neko-Agent 互动，Neko-Agent 具备角色扮演、Minecraft 知识问答、服务器管理操作等能力。

## 特性

- **自然对话** — 支持 `/agent <消息>` 命令和聊天触发（默认包含 Neko-Agent 名字即触发）
- **角色扮演** — 完全可自定义的 AI 人设，默认设定为傲娇猫娘
- **操作执行** — AI 可通过 `[名字:指令:参数]` 格式执行游戏内操作（传送、给予物品、改天气等）
- **多语言** — 自动检测玩家客户端语言，支持简体中文 / 繁体中文 / English
- **记忆系统** — Neko-Agent 可记住玩家告诉它的事，下次对话时自动调用
- **坐标管理** — 玩家可保存命名坐标，Neko-Agent 可帮玩家导航
- **玩家画像** — 自动分析玩家行为特征，生成玩家专属画像
- **项目追踪** — 记录玩家正在进行的 Minecraft 工程项目
- **配方查询** — `/agent recipe <物品名>` 查询合成配方
- **服务器日记** — 每日自动生成服务器运营日记，统计登录/死亡/AI 调用等数据
- **死亡吐槽** — 玩家死亡时 Neko-Agent 傲娇吐槽 + 给出生存建议
- **进服欢迎** — 玩家上线时 Neko-Agent 主动打招呼
- **成就庆祝** — 玩家获得成就时 Neko-Agent 发来贺电
- **空闲提醒** — 服务器一段时间无人聊天时 Neko-Agent 主动冒泡
- **敏感词过滤** — 可配置敏感词列表，命中直接拦截
- **限流熔断** — 全服和单人调用频率限制，防止 API 滥用
- **配置热重载** — `/agent reload` 无需重启服务器即可更新所有配置

## 环境要求

- Paper 26.2+
- Java 25
- OpenAI 兼容 API Key（必需，DeepSeek/OpenAI 等均可）
- Kimi API Key（可选，用于联网搜索功能）

## 安装

1. 从 [Releases](https://github.com/your-repo/agent/releases) 下载最新版 `NekoAgent-*.jar`
2. 放入服务器的 `plugins/` 目录
3. 重启服务器（或使用 PlugMan 等插件管理器加载）
4. 编辑 `plugins/Neko-Agent/config.yml`，填入 API Key
5. 执行 `/agent reload` 或重启服务器使配置生效

## 快速开始

### 1. 配置 API Key

编辑 `plugins/Neko-Agent/config.yml`：

```yaml
openai:
  api_key: "sk-xxxxxxxxxxxxxxxx"  # 你的 API Key
  base_url: "https://api.deepseek.com"  # 或其他兼容接口地址
  model: "deepseek-v4-flash"      # 模型名称
```

### 2. 自定义 Neko-Agent 名字

```yaml
agent:
  name: "小绘"          # Neko-Agent 名字（聊天触发词 + 操作标签名）
  trigger_prefix: "小绘" # 聊天触发前缀
```

### 3. 玩家使用

玩家在聊天中喊 Neko-Agent 名字即可触发对话，或使用命令：

```
/agent 你好
/agent 怎么建刷怪塔
```

## 命令列表

| 命令                             | 说明               | 权限            |
| ------------------------------ | ---------------- | ------------- |
| `/agent`                       | 显示帮助             | 所有玩家          |
| `/agent <消息>`                  | 与 Neko-Agent 对话       | 所有玩家          |
| `/agent remember <内容>`         | 让 Neko-Agent 记住关于你的信息 | 所有玩家          |
| `/agent forget <编号>`           | 删除一条记忆           | 所有玩家          |
| `/agent memories`              | 查看 Neko-Agent 记住了你什么  | 所有玩家          |
| `/agent clear`                 | 清除对话历史           | 所有玩家          |
| `/agent loc <名称>`              | 保存当前位置           | 所有玩家          |
| `/agent loc list`              | 列出已保存坐标          | 所有玩家          |
| `/agent loc remove <名称>`       | 删除坐标             | 所有玩家          |
| `/agent stats`                 | 查看统计数据           | 所有玩家          |
| `/agent profile`               | 查看玩家画像           | 所有玩家          |
| `/agent profile set <标签> <内容>` | 设置自己的画像标签        | 所有玩家          |
| `/agent project`               | 列出项目             | 所有玩家          |
| `/agent project <名称>`          | 创建新项目            | 所有玩家          |
| `/agent tell <玩家> <消息>`        | 让 Neko-Agent 给指定玩家传话  | 所有玩家          |
| `/agent mute`                  | 切换 Neko-Agent 静音模式    | 所有玩家          |
| `/agent recipe <物品名>`          | 查询合成配方           | 所有玩家          |
| `/agent diary`                 | 查看最近日记           | 所有玩家          |
| `/agent diary <日期>`            | 查看指定日期日记         | 所有玩家          |
| `/agent diary list`            | 列出所有日记           | 所有玩家          |
| `/agent memories <玩家>`         | 查看其他玩家的记忆        | `agent.admin` |
| `/agent profile <玩家>`          | 查看其他玩家的画像        | `agent.admin` |
| `/agent logs <类型>`             | 查看日志             | `agent.admin` |
| `/agent reload`                | 重载配置文件           | `agent.admin` |
| `/agent clearall`              | 清除所有玩家对话历史       | `agent.admin` |

### 日志查看（管理员）

```
/agent logs stats       — 今日统计摘要
/agent logs cost        — 今日 API 费用
/agent logs chat        — 最近对话记录
/agent logs errors      — 最近错误
/agent logs player <名> — 指定玩家记录
/agent logs search <词> — 搜索对话
```

## 操作指令

AI 回复中可包含操作指令来执行游戏内操作，格式为 `[名字:指令:参数]`：

| 指令 | 功能 | 示例 |
|------|------|------|
| `[名字:tp:x:y:z]` | 传送玩家 | `[小绘:tp:100:64:-200]` |
| `[名字:give:物品:数量]` | 给予物品 | `[小绘:give:diamond:5]` |
| `[名字:time:值]` | 设置时间 | `[小绘:time:day]` |
| `[名字:weather:类型]` | 更改天气 | `[小绘:weather:clear]` |
| `[名字:effect:效果:秒:等级]` | 施加状态 | `[小绘:effect:speed:60:2]` |
| `[名字:spawn:类型:n]` | 生成实体 | `[小绘:spawn:cow:3]` |
| `[名字:locate:结构]` | 定位建筑 | `[小绘:locate:village]` |
| `[名字:locatebiome:群系]` | 定位群系 | `[小绘:locatebiome:cherry_grove]` |
| `[名字:search:关键词]` | 联网搜索 | `[小绘:search:1.21 新内容]` |
| `[名字:remember:内容]` | 记住信息 | `[小绘:remember:玩家喜欢红石]` |
| `[名字:saveloc:名称]` | 保存坐标 | `[小绘:saveloc:家]` |
| `[名字:goloc:名称]` | 传送至坐标 | `[小绘:goloc:家]` |

## 权限

| 权限            | 说明               | 默认 |
| ------------- | ---------------- | -- |
| `agent.admin` | 管理权限（重载、清除、查看日志） | OP |

## 配置参考

完整配置项说明见 `plugins/Neko-Agent/config.yml`。关键配置：

```yaml
# AI API（OpenAI 兼容）
openai:
  api_key: ""                             # API 密钥（必填）
  base_url: "https://api.deepseek.com"    # API 地址（支持任意兼容接口）
  model: "deepseek-v4-flash"              # 模型名称（取决于你的 API 提供商）
  temperature: 0.7                        # 创造性 (0-2)
  timeout_seconds: 60                     # 超时时间

# Kimi 联网搜索（可选）
kimi:
  enabled: false                          # 启用联网搜索
  api_key: ""
  model: "kimi-k2.5"

# 服务器信息
server_name: "Minecraft Server"           # 服务器名称（占位符用）

# Neko-Agent 配置
agent:
  name: "小绘"                            # Neko-Agent 名字
  system_prompt: >                        # AI 人设提示词（支持 {server_name}/{agent_name} 占位符）
    ...
  trigger_prefix: "小绘"                  # 聊天触发前缀
  trigger_mode: "contains"                # contains / prefix / exact
  cooldown_seconds: 3                     # 冷却时间（秒）
  max_history: 10                         # 最大保留对话轮数

# 限流
rate_limit:
  global_max_per_minute: 30               # 全服每分钟上限
  player_max_per_minute: 10               # 单人每分钟上限

# 敏感词
filter:
  sensitive_words:
    - "敏感词1"
    - "敏感词2"

# 日志
logs:
  debug: false                            # 调试模式
```

## 多语言

插件自动检测玩家客户端语言，支持：

- `zh_cn` — 简体中文
- `zh_tw` / `zh_hk` — 繁体中文（zh_hant）
- 其他 — English（回退）

语言文件位于 `plugins/Neko-Agent/` 目录：`messages_zh.yml`、`messages_zh_hant.yml`、`messages_en.yml`。

## 常见问题

**Q: AI 不回复？**\
检查 `openai.api_key` 是否正确填写，执行 `/agent reload` 后重试。查看 `logs/latest.log` 排查错误。

**Q: 如何更改 Neko-Agent 性格？**\
编辑 `config.yml` 中的 `agent.system_prompt`，修改人设描述，然后 `/agent reload`。

**Q: 如何禁用联网搜索？**\
设置 `kimi.enabled: false` 即可。

**Q: 聊天喊 Neko-Agent 名字不触发？**\
确认 `trigger_mode` 设置正确（`contains` 表示消息中包含名字即触发），确认 `trigger_prefix` 与 `agent.name` 匹配。

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/NekoAgent-*.jar`。

## 许可证

MIT
