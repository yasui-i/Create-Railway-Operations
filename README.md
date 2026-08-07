# Create: Railway Operations

[English](#english) | [中文](#中文)

---

## English

**Create: Railway Operations** is a NeoForge addon for [Create](https://github.com/Creators-of-Create/Create) that enhances train operations with schedule-based door control, station announcement broadcasts, and ghost seats.

### Features

**Schedule-Based Door Control**
- Add Door Control as a schedule condition to control which side doors open at stations.
- Configure direction: All / North / East / South / West.
- Follow Station mode: reads the station's own door setting while suppressing auto-open, so the schedule takes full control.

**Train Announcements (Broadcasts)**
- Add Train Announcement as a schedule condition to play audio on carriages.
- Audio plays via OpenAL with real-time positional tracking, following the carriage.
- Configurable delay with unit selection: Ticks / Seconds / Minutes.

**Audio Pack System**
- Audio packs load from `config/railway_operations/audio/` as `.zip` files or directories.
- `pack.json` supports simple mode (single file) and template mode (language + station name splicing).
- Server builds a binary bundle of all packs and syncs to clients during the **configuration phase** (before the player joins the world), using SHA-256 verification and chunked transfer.
- On-demand lazy-load fallback for clients that miss chunks during sync.

**Ghost Seat**
- Invisible, no-collision seat based on Create's SeatBlock.
- Right-click or fall onto it to sit; entities walk through freely.
- Fully compatible with train contraption assembly.

### Commands

All commands require permission level 2.

| Command | Description |
|---------|-------------|
| `/railway reload` | Reload audio packs on the server |
| `/railway status` | Show loaded audio packs and broadcast counts |
| `/railway sync` | Push all audio packs to all connected players |

### Installation

1. Requires **Create 6.0.10+** and **NeoForge 21.1.219+** for Minecraft 1.21.1.
2. Place `railway_operations-1.0.0.jar` in the `mods/` folder on both server and client.
3. Place audio packs in `config/railway_operations/audio/` on the **server**.
4. Run `/railway reload` then `/railway sync` to push packs to online players.
5. New players joining will receive packs automatically during the configuration handshake.

### Audio Pack Format

Simple mode (single file):
```json
{
  "name": "Shanghai Bureau Fuxing EMU",
  "description": "Fuxing EMU Shanghai Bureau Broadcasts",
  "broadcasts": {
    "welcome": { "simple": "welcome.ogg" },
    "door_closing": { "simple": "door_closing.ogg" },
    "arrival": { "simple": "arrival.ogg" }
  }
}
```

Template mode (language + station splicing):
```json
{
  "name": "JR East E5",
  "broadcasts": {
    "arrival": {
      "template": { "zh": "arrival_zh.ogg", "en": "arrival_en.ogg" },
      "has_station": true
    }
  }
}
```

### Schedule Example

```
DESTINATION: Beijing South
  Group 1: [Announcement: door_closing, delay 12s] [Delay 15s]
  Group 2: [Door Control: Follow Station, 20s]
→ Announcement plays 3s before doors close.
```

---

## 中文

**Create: Railway Operations** 是基于 [Create](https://github.com/Creators-of-Create/Create) 的 NeoForge 附属模组，为列车运营增加时刻表车门控制、列车广播报站和幽灵坐垫功能。

### 功能

**时刻表车门控制**
- 在时刻表中添加车门控制条件，控制到站后开启哪一侧车门。
- 可选方向：全部 / 北侧 / 东侧 / 南侧 / 西侧。
- 跟随车站模式：读取车站自身车门设置并拦截自动开门，让时刻表完全接管车门时机。

**列车广播报站**
- 添加列车广播条件，在车厢上播放音频。
- 音频通过 OpenAL 实时位置追踪跟随车厢移动。
- 可配置播放延时及单位：Tick / 秒 / 分钟。

**音频包系统**
- 音频包从 `config/railway_operations/audio/` 加载，支持 `.zip` 文件和目录格式。
- `pack.json` 支持简单模式（单文件）和模板模式（语言+站名拼接）。
- 服务端将所有音频包打包为二进制 bundle，在玩家加入前的**配置握手阶段**通过分块传输同步到客户端，采用 SHA-256 校验。
- 懒加载回退：同步丢失的音频可在运行时按需获取。

**幽灵坐垫**
- 基于 Create 原版 SeatBlock 的透明无碰撞坐垫。
- 右键或摔落可乘坐，实体自由穿行。
- 完全兼容列车结构组装。

### 指令

所有指令需要权限等级 2。

| 指令 | 说明 |
|------|------|
| `/railway reload` | 重新加载服务端音频包 |
| `/railway status` | 查看已加载的音频包及广播数量 |
| `/railway sync` | 将全部音频包推送给所有在线玩家 |

### 安装

1. 需要 **Create 6.0.10+** 和 **NeoForge 21.1.219+**（Minecraft 1.21.1）。
2. 将 `railway_operations-1.0.0.jar` 放入服务端和客户端的 `mods/` 文件夹。
3. 在**服务端**的 `config/railway_operations/audio/` 放入音频包。
4. 执行 `/railway reload` 然后 `/railway sync` 推送给在线玩家。
5. 新玩家加入时会在配置握手阶段自动接收音频包。

### 音频包格式

简单模式（单文件）：
```json
{
  "name": "上海局复兴号",
  "description": "复兴号动车组上海局报站广播",
  "broadcasts": {
    "始发欢迎词": { "simple": "始发欢迎词.ogg" },
    "关门前提示": { "simple": "关门前提示.ogg" },
    "终到广播": { "simple": "终到广播.ogg" }
  }
}
```

模板模式（语言+站名拼接）：
```json
{
  "name": "JR东日本 E5系",
  "broadcasts": {
    "arrival": {
      "template": { "zh": "arrival_zh.ogg", "en": "arrival_en.ogg" },
      "has_station": true
    }
  }
}
```

### 时刻表配置示例

```
目的地: 北京南站
  条件组1: [列车广播: 关门前提示, 延时12s] [延时15s]
  条件组2: [车门控制: 跟随车站, 20s]
→ 关门前3秒播放提示，车门按车站方向开启。
```
