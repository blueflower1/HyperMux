# HyperMux Android TV（计划中)

Android TV 客户端（Kotlin + Compose for TV + Media3），UI/交互移植自手机端 `hyper-mux-android`，复用后端 `/api/app/*` 接口（零改动）。

- **TV 端设计（当前有效）**：`docs/plans/48-tv-app-detailed.md`
- 移植母本：兄弟工作区 `hyper-mux-android`（手机端 0.1.11）——`ui/theme`、`ui/common` 近乎原样平移，`data/`、`player/PlayerController|ViewModel` 同语义复用
- 已作废：`../docs/plans/40-app-detailed.md`（Leanback + XML Fragment 旧方案，未实施）
