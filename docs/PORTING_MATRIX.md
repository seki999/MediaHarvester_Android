# Windows → Android 移植矩阵

## 实际审阅基线（2026-10-23）

原仓库 https://github.com/seki999/MediaHarvester ，commit `43d66e354027b9f617cc846fbb6234a3d99a0051`。
在本工程 `.upstream/MediaHarvester` 只读克隆；没有改动原 Windows 工作目录、源仓库或远端。
实际读取 README.md、LICENSE、THIRD_PARTY_NOTICES.md、licenses/yt-dlp/00-LICENSE，
models.py、extractors.py、download.py、database.py、files.py、security.py，
tests/test_core.py、test_recovery_extra.py、test_cancellation.py、test_media.py，docs/validation.md。
Windows 的记录包括 Instagram 专用解析、Cookie 粘贴和自动读取浏览器；这些不是 Android 已完成能力。
未在本次环境重跑 Windows Qt/EXE 测试，也没有用原仓库历史测试结果代替 Android 测试。

| Windows 实现 / 证据 | Android 决策 | 当前边界 |
|---|---|---|
| PySide6 app/ui，托盘 | Kotlin Compose Material 3，四个导航页，ViewModel | 原生界面，不含 WebView |
| models.py State + SQLite WAL/FULL | domain 状态机，Room WAL/FULL，CAS 状态更新 | 进程重启默认暂停；用户恢复 |
| download.py HttpBackend，test_core resume/416 | OkHttp，强 ETag + 严格 Content-Range/长度验证 | 弱 ETag/无验证器从头下载；416保守重下，不从长度推断成功 |
| DownloadManager 并发、重试 | 协程下载队列，1–4并发，有限指数退避 | 限速按任务；不是全队列总速率 |
| _commit + commit.json | SAVING 记录 URI/摘要，目标重读校验后提交 | MediaStore 分配与 Room 日志之间仍有跨存储崩溃窗口，详见架构 |
| files.py publish + sha256 | MediaStore/SAF 自动编号、SHA-256 图片去重 | 并发发布串行；不会覆盖已有目标 |
| HTML 图片、srcset、lazy、picture、OG、背景 | jsoup 静态 HTML 提取，图片网格、批量选择、宽度/格式筛选 | 无 JS 渲染；选择最大声明候选，尚无多候选坏图自动回退 |
| yt-dlp Python API，真实 formats，播放列表 | Android 原生运行时封装，JSON 格式/播放列表，禁用 DRM/live | 播放列表最多100条；尚未逐站在线验收 |
| FFmpeg 合并、音频转换、验证 | Android FFmpeg，yt-dlp合并，直接媒体转换，Android媒体轨道检查 | 编解码器不支持则失败；不把分轨下载成功标成完成 |
| aria2 optional | 不包含，OkHttp 为直接文件引擎 | 保留统一 DownloadEngine 接口 |
| Playwright optional | 不包含 | 静态图片提取降级，不尝试验证码/DRM绕过 |
| Windows 浏览器 Cookie / 粘贴 Cookie | 明确授权单网站 Netscape 手动导入，Keystore AES-GCM | 不读取 Android 浏览器；不自动读取任何登录态 |
| Windows 路径 + 任意文件 | Scoped Storage MediaStore、SAF | 不申请 MANAGE_EXTERNAL_STORAGE |
| 公网固定 DNS + 外部引擎认证代理 | OkHttp 连接/重定向公网检查 | yt-dlp 子进程未接入等效公网代理；不是完整 Windows 网络隔离等价物 |
| Windows 后台线程 | 用户启动 dataSync 前台服务 + 通知 + 有界唤醒锁 | Android 系统预算、Doze、厂商省电仍可中断；强停不承诺自动运行 |

## 下载引擎评估

| 方案 | Android 兼容性 / 更新 | 包体 / 许可证 | 决定 |
|---|---|---|---|
| 桌面 Python/Windows EXE | Windows二进制不能在Android运行 | 不适用 | 排除 |
| Chaquopy + pip yt-dlp | 需额外验证 Python、原生依赖和FFmpeg、进程取消 | 仍需运行时，许可证需逐依赖检查 | 没有选择，不能假设 pip 即可运行 |
| youtubedl-android 0.18.1 | 专用 Android Python / yt-dlp / QuickJS 和 FFmpeg .so；arm64/x86_64 | 封装 GPL-3.0；原生包显著增加体积 | 采用并实际构建、设备验证 |
| FFmpegKit | 不依赖已停维护的发行包；必须有持续可取得的二进制/源码 | 按构建可能LGPL/GPL | 没有选择 |
| 自行 NDK 构建 Python/FFmpeg | 能掌握16KB页面、版本、完整源码和安全更新 | 工程量高；GPL/LGPL对应源码义务 | 正式分发前的推荐后续路径 |

已读取 youtubedl-android tag `0.18.1`（`d725d5c9a18c3a99a13ee0308bf78275dc310760`）
的 README、LICENSE、YoutubeDL.kt、YoutubeDLRequest.kt、StreamProcessExtractor.kt、FFmpeg.kt、
BUILD_PYTHON.md、BUILD_FFMPEG.md、Gradle文件。README中旧Python说明不作为实际二进制版本证据。

更新策略：固定 Maven 版本，通过依赖升级、重新构建APK更新；不静默下载运行时代码。
封装库具备 updateYoutubeDL 接口，本版未开放应用内下载更新，避免不可复现和未知运行时兼容。
每次更新须重跑Range、取消、Native合并、Android启动及16KB审核；旧库可能不支持将来的yt-dlp最低Python要求。
实际包体与 ELF 检查见 `validation.md` / `native-audit.json`。

来源：
- https://github.com/yausername/youtubedl-android/tree/0.18.1
- https://github.com/yausername/youtubedl-android/blob/0.18.1/LICENSE
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/guide/practices/page-sizes
