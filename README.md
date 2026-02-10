# BetterProjectorOverlay

一个专注于 **超速投影冲突提示** 的 Mindustry 客户端模组。  
This is a client-side Mindustry mod focused on **overdrive conflict detection**.

## 中文说明

### 功能
- 手持 `超速投影` / `超速穹顶` 时，自动预判鼠标落点放置后电网会偏向正电还是负电。
- 当 `超速投影` 落在 `超速穹顶`（以及加布/模组超速投影）范围内时，使用 MindustryX `MarkerType.newMarkFromChat` 在地图上打标。
- 提供聊天栏通知开关：触发标记时发送 `<BPO><应拆除超速>(x,y)`，同坐标只播报一次。
- 内置中英文本适配。

### 安装
- 从 Releases 下载产物：
  - `betterProjectorOverlay-<version>.zip`
  - `betterProjectorOverlay-<version>.jar`
  - `betterProjectorOverlay-<version>-android.jar`
- 放入 Mindustry 的 `mods` 目录并重启。

### 开发构建
```bash
gradle deploy
```

## English

### Features
- While holding `Overdrive Projector` / `Overdrive Dome`, it predicts whether the hovered placement tends to make affected power grids positive or negative.
- When an `Overdrive Projector` is inside an `Overdrive Dome` (and modded/gaobu-style overdrive source) range, it marks the projector on the map through MindustryX `MarkerType.newMarkFromChat`.
- Adds a chat-toggle alert: `<BPO><Need remove overdrive>(x,y)` is sent once per coordinate.
- Includes Chinese/English localization.

### Install
- Download one of these artifacts from Releases:
  - `betterProjectorOverlay-<version>.zip`
  - `betterProjectorOverlay-<version>.jar`
  - `betterProjectorOverlay-<version>-android.jar`
- Put it into your Mindustry `mods` folder and restart the game.

### Build
```bash
gradle deploy
```
