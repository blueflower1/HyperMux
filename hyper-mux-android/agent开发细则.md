# Agent 开发细则（HyperMux Android）

> 本文件是本项目 AI Agent 的工作守则。接到任务先读这里，再读 README.md / docs/ 相关章节。

## 1. 总原则：轻思考，直接干

- **不要把简单任务想复杂**。接到改动：定位文件 → 改 → 编译 → 交给开发者真机验证。能一步说清的不要推断三层。
- 不确定的地方**问一句**比脑补一整条假设链好。
- 不重复分析已确认的结论；docs/ 里写过根因和做法的，直接查，不要重新"排查一遍"。
- 回复简洁：改了什么、为什么（一两行）、下一步验证方式。

## 2. adb 与 USB 调试：默认已就绪，直接用

- **默认假设 adb 可用、真机已连接、USB 调试已授权**。不要先问"设备连上了吗"，直接 `adb devices` 干活；万一没设备再提示开发者接 USB 线/点授权。
- adb 路径：`D:\dev_home\android-sdk\platform-tools\adb.exe`（PowerShell 里用 `& $adb ...` 调用）。
- 包名 `com.hypermux.mobile`，主 Activity `.ui.MainActivity`。
- 常用动作（完整标准查法见 docs/build-and-acceptance.md §13）：

```powershell
$adb = "D:\dev_home\android-sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb install -r .\app\build\outputs\apk\<debug|release>\<变体>.apk   # 签名与手机已装包要一致
& $adb shell am start -n com.hypermux.mobile/.ui.MainActivity
& $adb shell screencap -p /sdcard/s.png; & $adb pull /sdcard/s.png "$env:TEMP\s.png"
& $adb logcat -c   # 出问题先看日志
```

- **已知坑（必须遵守）**：
  - 截屏**不要**用 `adb exec-out screencap -p > x.png`（PowerShell 文本编码会写坏 PNG），一律 `screencap -p /sdcard/...` + `pull`。
  - `dumpsys window windows` 输出是 UTF-16，落盘后用 `Get-Content` 读，别用 read 工具打开。
  - 装包必须 `-r`，且 debug/release 签名不同，装与手机同签名的变体。
  - 每次改完要验证可见效果时，**versionName/versionCode 要 +1**，dist 只留当前版本包（避免手机装到旧包误判"未修复"）。

## 3. 测试策略：少写测试，多交真机手测

- **默认不写单元测试 / 仪器化测试**。本项目以真机手动验收为准（验收标准在 docs/plans/45-phone-app-detailed.md 和 docs/build-and-acceptance.md）。
- 改动的验证责任**交给开发者**：agent 改完代码后交付物是"能编译通过的包 + 一句话测法"，例如：
  > "已重新打包 0.1.4，真机装包后进 首页→黄雀→播放页 竖滑两次，看亮度条是否跟手。"
- 开发者反馈"有问题"时，**先 adb 取证据再下结论**，不要凭截图/描述直接猜改法：
  1. `adb logcat -c` 后重现，抓 crash / 异常栈；
  2. 截屏 + `uiautomator dump` 取节点实际 bounds（UI 尺寸/裁切类第一现场）;
  3. 必要时 `dumpsys window windows` 看窗口 frame/fit 内缩;
  4. 需要运行时真实数值就加**临时 Log 埋点**（带明确 Tag，看完即删）。
- 只有当逻辑本身难以手测（如纯函数计算、数据结构变换、解析器）时才考虑补少量单元测试，且一次只补必要的断言。

## 4. 构建环境（Windows，勿动）

```powershell
$env:JAVA_HOME = "D:\DEV_HOME\jdk17"
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"   # 中文 Windows 必需
D:\dev_home\gradle-8.12.1\bin\gradle.bat -p . assembleDebug --no-daemon
```

- 不要改动 JDK / Gradle / SDK 路径，不要升级依赖版本，不要"顺手"重构 Gradle 脚本。
- 中文源码必须保持 UTF-8；改文件注意编码，不要引入 GBK 乱码。
- release 包：`assembleRelease`（缺 keystore.properties 时自动退 debug 签名，仅本地测试）。
- **交付包一律打进现有 `dist/` 路径**：用 `assembleDist` 任务（构建 debug+release 并复制重命名为 `dist/hypermux-mobile-<versionName>-<debug|release>.apk`），不要把包留在 `app/build/outputs/` 或别处让开发者自己找。
```powershell
D:\dev_home\gradle-8.12.1\bin\gradle.bat -p . assembleDist --no-daemon
```

## 5. 代码与项目约定

- 跟随现有结构：`data/api` 的 ApiService 与后端契约 1:1，DTO 字段同名，**不要单方面"优化"接口字段名**。
- 响应 `R<T>{code,msg,data}`，`code==0` 成功；时间一律毫秒时间戳；注意 `videos[].duration` 是**秒**、`progress.*Ms` 是毫秒。
- UI 一律 Jetpack Compose + Material 3（深色默认）；状态管理走 ViewModel；DI 走 Hilt。
- 改动最小化：只改任务需要的地方，不顺手改无关代码、不改注释风格、不动 res/ 布局结构（本项目基本无 XML 布局）。
- 业务阈值常量统一放 `config/AppConfig.kt`，不要散落魔法数字。
- 新增中文注释/日志文案保持 UTF-8 无乱码；日志 Tag 稳定可过滤。

## 6. 交付流程（每次任务）

1. 改代码（最小改动）。
2. `assembleDist` 打包，产物落到 `dist/hypermux-mobile-<versionName>-<变体>.apk`。
3. 需要装真机/验证可见效果的，顺手 `adb install -r` + 版本号 +1。
4. 交付："改了什么 + 一句话真机测法"。测法要具体到页面和动作，开发者照做即可。
5. 反馈有问题 → 走 §3 的 adb 取证流程，用实测数值定案，不猜。
