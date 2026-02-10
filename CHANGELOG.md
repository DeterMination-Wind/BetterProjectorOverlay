# Changelog

## v1.0.0
- ✨ 首发 BetterProjectorOverlay：手持超速投影/超速穹顶时显示放置后正负电预判。
- 🗺️ 新增超速冲突扫描：检测“超速投影位于超速穹顶或加布超速范围内”并调用 MindustryX 地图标记接口。
- 💬 新增聊天栏通知开关与坐标去重播报，消息格式为 `<BPO><应拆除超速>(x,y)`（中英适配）。
- 📦 补充 Release 流水线：按 tag 自动构建并发布 zip/jar/android.jar，并自动提取对应更新日志。
