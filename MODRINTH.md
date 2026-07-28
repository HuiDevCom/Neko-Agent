# Neko Agent - Minecraft AI 助手

为 Minecraft 服务器增加一位可对话、会操作、有性格的 AI 助手。接入 DeepSeek / OpenAI 等大模型，玩家在聊天中喊名字即可触发对话，AI 可通过操作指令执行传送、给予物品、改天气等游戏内操作。

## 功能

- **自然对话** — `/agent <消息>` 命令或聊天喊名字触发对话
- **角色扮演** — 完全可自定义 AI 人设，默认傲娇猫娘
- **操作指令** — AI 通过 `[名字:指令:参数]` 执行游戏内操作
  - `tp` 传送、`give` 给予物品、`time` 调时间、`weather` 改天气
  - `effect` 状态效果、`spawn` 生成实体、`locate` 定位建筑
  - `locatebiome` 定位群系、`search` 联网搜索
- **记忆系统** — 记住玩家告诉它的事，下次对话自动调用
- **坐标管理** — 保存命名坐标，可帮玩家导航
- **玩家画像** — 自动分析玩家特征
- **配方查询** — `/agent recipe <物品名>` 查合成配方
- **服务器日记** — 每日自动生成运营日记
- **死亡吐槽** — 玩家死亡时 AI 吐槽 + 给出生存建议
- **进服欢迎 / 成就庆祝** — 主动打招呼和祝贺
- **多语言** — 自动检测客户端语言，支持简中 / 繁中 / English
- **敏感词过滤** — 可配置拦截词
- **限流熔断** — 全服和单人调用频率限制
- **配置热重载** — `/agent reload` 无需重启
- **多命令入口** — 支持 `/agent`、`/nekoagent`、`/na`

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/agent <消息>` | 与 Neko-Agent 对话 | 所有玩家 |
| `/agent remember <内容>` | 让 Neko-Agent 记住信息 | 所有玩家 |
| `/agent forget <编号>` | 删除一条记忆 | 所有玩家 |
| `/agent memories` | 查看记忆列表 | 所有玩家 |
| `/agent clear` | 清除对话历史 | 所有玩家 |
| `/agent loc <名称/list/remove>` | 管理坐标 | 所有玩家 |
| `/agent stats` | 查看统计数据 | 所有玩家 |
| `/agent profile [玩家]` | 查看玩家画像 | 所有玩家 |
| `/agent project [名称]` | 管理工程项目 | 所有玩家 |
| `/agent tell <玩家> <消息>` | 传话给指定玩家 | 所有玩家 |
| `/agent mute` | 切换静音模式 | 所有玩家 |
| `/agent recipe <物品名>` | 查询合成配方 | 所有玩家 |
| `/agent diary [日期/list]` | 查看服务器日记 | 所有玩家 |
| `/agent logs <类型>` | 查看日志 | `agent.admin` |
| `/agent reload` | 重载配置 | `agent.admin` |
| `/agent clearall` | 清除所有历史 | `agent.admin` |

> `/nekoagent` 和 `/na` 可作为 `/agent` 的别名使用。

## 环境

- Paper 26.2+ 及其分支（Purpur、Pufferfish 等）
- Java 25

## 配置

编辑 `plugins/Neko-Agent/config.yml`，填入 API Key 即可使用。支持任意 OpenAI 兼容接口（DeepSeek、OpenAI、本地模型等）。

```yaml
openai:
  api_key: "sk-xxxxxxxxxxxxxxxx"
  base_url: "https://api.deepseek.com"
  model: "deepseek-v4-flash"
```

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| `agent.admin` | 管理权限（重载、查看日志、清除所有历史） | OP |