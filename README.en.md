<p align="center"><img src="src/main/resources/icon.png" width="128" alt="Neko Agent"></p>

<p align="center"><b>English</b> | <a href="README.md">中文</a></p>

# Neko Agent - Minecraft AI Assistant Plugin

An AI assistant plugin for Minecraft Paper servers powered by OpenAI-compatible APIs. Supports DeepSeek, OpenAI, and any compatible endpoints. Players can interact with Neko-Agent through chat or commands — Neko-Agent features role-playing, Minecraft knowledge Q&A, server management operations, and more.

## Features

- **Natural Conversation** — Chat via `/agent <message>` or trigger by mentioning Neko-Agent's name in chat
- **Role-Playing** — Fully customizable AI persona, defaults to a tsundere catgirl
- **Action Execution** — AI executes in-game actions via `[name:command:params]` format (teleport, give items, change weather, etc.)
- **Multi-Language** — Auto-detects player client language, supports Simplified Chinese / Traditional Chinese / English
- **Memory System** — Neko-Agent remembers things players tell it, recalls them in future conversations
- **Location Management** — Save named coordinates, Neko-Agent helps players navigate
- **Player Profiles** — Automatically analyzes player behavior and generates profiles
- **Project Tracking** — Tracks players' ongoing Minecraft building projects
- **Recipe Lookup** — `/agent recipe <item>` to search crafting recipes
- **Server Diary** — Auto-generates daily server operation diary with login/death/AI call statistics
- **Death Roasts** — Neko-Agent playfully roasts players on death + gives survival tips
- **Join Welcome** — Neko-Agent greets players when they log in
- **Achievement Celebration** — Neko-Agent congratulates players on achievements
- **Idle Reminders** — Neko-Agent chimes in when the server is quiet
- **Sensitive Word Filter** — Configurable word list, blocks matched messages
- **Rate Limiting** — Global and per-player rate limits to prevent API abuse
- **Hot Reload** — `/agent reload` updates all configs without server restart

## Requirements

- Paper 26.2+
- Java 25
- OpenAI-compatible API Key (required; DeepSeek, OpenAI, etc.)
- Kimi API Key (optional, for web search)

## Installation

1. Download the latest `NekoAgent-*.jar` from [Releases](https://github.com/your-repo/neko-agent/releases)
2. Place it in the server's `plugins/` directory
3. Restart the server (or use a plugin manager like PlugMan)
4. Edit `plugins/Neko-Agent/config.yml` and fill in your API Key
5. Run `/agent reload` or restart the server

## Quick Start

### 1. Configure API Key

Edit `plugins/Neko-Agent/config.yml`:

```yaml
openai:
  api_key: "sk-xxxxxxxxxxxxxxxx"  # Your API Key
  base_url: "https://api.deepseek.com"  # Or any compatible endpoint
  model: "deepseek-v4-flash"      # Model name
```

### 2. Customize Neko-Agent Name

```yaml
agent:
  name: "Neko"           # Neko-Agent name (chat trigger word + action tag name)
  trigger_prefix: "Neko" # Chat trigger prefix
```

### 3. Player Usage

Players trigger conversation by mentioning Neko-Agent's name in chat, or use commands:

```
/agent hello
/agent how do I build a mob farm
```

## Commands

| Command                         | Description                    | Permission      |
| ------------------------------- | ------------------------------ | --------------- |
| `/agent`                        | Show help                      | All players     |
| `/agent <message>`              | Chat with Neko-Agent           | All players     |
| `/agent remember <content>`     | Tell Neko-Agent something      | All players     |
| `/agent forget <index>`         | Remove a memory                | All players     |
| `/agent memories`               | View your memories             | All players     |
| `/agent clear`                  | Clear chat history             | All players     |
| `/agent loc <name>`             | Save current location          | All players     |
| `/agent loc list`               | List saved locations           | All players     |
| `/agent loc remove <name>`      | Remove a location              | All players     |
| `/agent stats`                  | View statistics                | All players     |
| `/agent profile`                | View your profile              | All players     |
| `/agent profile set <tag> <v>`  | Set a profile tag              | All players     |
| `/agent project`                | List projects                  | All players     |
| `/agent project <name>`         | Create a project               | All players     |
| `/agent tell <player> <msg>`    | Have Neko-Agent message player | All players     |
| `/agent mute`                   | Toggle Neko-Agent mute mode    | All players     |
| `/agent recipe <item>`          | Look up crafting recipe        | All players     |
| `/agent diary`                  | View recent diary              | All players     |
| `/agent diary <date>`           | View diary for a date          | All players     |
| `/agent diary list`             | List all diary entries         | All players     |
| `/agent memories <player>`      | View another player's memories | `agent.admin`   |
| `/agent profile <player>`       | View another player's profile  | `agent.admin`   |
| `/agent logs <type>`            | View logs                      | `agent.admin`   |
| `/agent reload`                 | Reload configuration           | `agent.admin`   |
| `/agent clearall`               | Clear all players' history     | `agent.admin`   |

### Log Viewing (Admin)

```
/agent logs stats       — Today's summary
/agent logs cost        — Today's API cost
/agent logs chat        — Recent conversations
/agent logs errors      — Recent errors
/agent logs player <name> — Specific player's logs
/agent logs search <keyword> — Search conversations
```

## Action Commands

AI responses may contain action commands to execute in-game operations, using the format `[name:command:params]`:

| Command | Function | Example |
|---------|----------|---------|
| `[name:tp:x:y:z]` | Teleport player | `[Neko:tp:100:64:-200]` |
| `[name:give:item:count]` | Give items | `[Neko:give:diamond:5]` |
| `[name:time:value]` | Set time | `[Neko:time:day]` |
| `[name:weather:type]` | Change weather | `[Neko:weather:clear]` |
| `[name:effect:type:sec:lvl]` | Apply effect | `[Neko:effect:speed:60:2]` |
| `[name:spawn:type:n]` | Spawn entity | `[Neko:spawn:cow:3]` |
| `[name:locate:structure]` | Locate structure | `[Neko:locate:village]` |
| `[name:locatebiome:biome]` | Locate biome | `[Neko:locatebiome:cherry_grove]` |
| `[name:search:keyword]` | Web search | `[Neko:search:1.21 new features]` |
| `[name:remember:content]` | Remember info | `[Neko:remember:player likes redstone]` |
| `[name:saveloc:name]` | Save location | `[Neko:saveloc:home]` |
| `[name:goloc:name]` | Go to location | `[Neko:goloc:home]` |

## Permissions

| Permission     | Description                          | Default |
| -------------- | ------------------------------------ | ------- |
| `agent.admin`  | Admin (reload, clear, view logs)     | OP      |

## Configuration Reference

See `plugins/Neko-Agent/config.yml` for all options. Key settings:

```yaml
# AI API (OpenAI compatible)
openai:
  api_key: ""                             # API key (required)
  base_url: "https://api.deepseek.com"    # API endpoint (any compatible provider)
  model: "deepseek-v4-flash"              # Model name (depends on your provider)
  temperature: 0.7                        # Creativity (0-2)
  timeout_seconds: 60                     # Timeout

# Kimi Web Search (optional)
kimi:
  enabled: false                          # Enable web search
  api_key: ""
  model: "kimi-k2.5"

# Server Info
server_name: "Minecraft Server"           # Server name (for placeholders)

# Neko-Agent Settings
agent:
  name: "Neko"                            # Neko-Agent name
  system_prompt: >                        # AI persona prompt (supports {server_name}/{agent_name})
    ...
  trigger_prefix: "Neko"                  # Chat trigger prefix
  trigger_mode: "contains"                # contains / prefix / exact
  cooldown_seconds: 3                     # Cooldown between triggers (seconds)
  max_history: 10                         # Max conversation turns to keep

# Rate Limiting
rate_limit:
  global_max_per_minute: 30               # Global calls per minute
  player_max_per_minute: 10               # Per-player calls per minute

# Sensitive Words
filter:
  sensitive_words:
    - "badword1"
    - "badword2"

# Logging
logs:
  debug: false                            # Debug mode
```

## Multi-Language

The plugin auto-detects player client language and supports:

- `zh_cn` — Simplified Chinese
- `zh_tw` / `zh_hk` — Traditional Chinese
- Others — English (fallback)

Language files are located in `plugins/Neko-Agent/`: `messages_zh.yml`, `messages_zh_tw.yml`, `messages_en.yml`.

## FAQ

**Q: AI doesn't respond?**\
Check that `openai.api_key` is correctly set, run `/agent reload`, and retry. Check `logs/latest.log` for errors.

**Q: How to change Neko-Agent's personality?**\
Edit `agent.system_prompt` in `config.yml` with your desired persona, then `/agent reload`.

**Q: How to disable web search?**\
Set `kimi.enabled: false`.

**Q: Chat trigger not working?**\
Make sure `trigger_mode` is correct (`contains` = triggered when name appears in message), and `trigger_prefix` matches `agent.name`.

## Building

```bash
./gradlew build
```

The built artifact is at `build/libs/NekoAgent-*.jar`.

## License

MIT
