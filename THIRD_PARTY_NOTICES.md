# 第三方许可与原生运行时来源

Android组合分发按GPL-3.0处理。原Windows项目MIT许可及版权保存在`licenses/UPSTREAM-MIT.txt`；不改变原Windows许可证。
本项目LICENSE及wrapper完整GPL文本已保留。这里的清单不是对尚未取得的原生对应源码的虚假声明。

| 组件 | 固定版本 / 许可 | 来源 |
|---|---|---|
| AndroidX Compose / Material3 / Lifecycle / Activity / Room / Core / DocumentFile | 见dependencies.tsv；Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / Coroutines | 2.0.21 / 1.9.0；Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp / Okio | 4.12.0及解析后的依赖；Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| jsoup | 1.18.3；MIT | https://github.com/jhy/jsoup |
| Coil | 2.7.0；Apache-2.0 | https://github.com/coil-kt/coil |
| youtubedl-android library/common/ffmpeg | 0.18.1；GPL-3.0 | https://github.com/yausername/youtubedl-android/tree/0.18.1 |
| yt-dlp | 随包实际2025.11.12（模拟器读取）；Unlicense及组件附带许可 | https://github.com/yt-dlp/yt-dlp/tree/2025.11.12 |
| Android Python运行时 | 专用Android构建；PSF及原生依赖各自许可，版本不能以旧README推断 | https://github.com/yausername/youtubedl-android/blob/0.18.1/BUILD_PYTHON.md |
| Android FFmpeg | 随包实际7.1.1（x86_64）；配置enable-gpl、enable-version3，按GPL-3.0 | https://ffmpeg.org/ / https://github.com/termux/termux-packages |
| QuickJS | 随包；MIT，确切构建提交仍需溯源 | https://bellard.org/quickjs/ |
| Jackson、Commons IO/Compress等传递依赖 | 见实际坐标及POM元数据；主要Apache-2.0 | https://github.com/FasterXML/jackson / https://commons.apache.org/ |
| JUnit、AndroidX Test、MockWebServer | 仅测试，分别EPL-1.0、Apache-2.0、Apache-2.0 | https://junit.org/junit4/ / https://developer.android.com/training/testing / https://github.com/square/okhttp |

`docs/dependencies.tsv`记录实际解析坐标；`dependency-artifacts.json`保留缓存Maven包大小和SHA256；
`dependency-license-metadata.json`保留POM声明。`licenses/managed/`保留能够从实际JAR/AAR取得的LICENSE/NOTICE。
应用assets包含licenses目录，不用META-INF冲突处理代替许可保留。
`docs/native-runtime.txt`是模拟器实际FFmpeg版本和编译选项，不代表arm64二进制已经执行。
`docs/native-audit.json`检查APK内原生ZIP中的ELF，不能只检查APK顶层.so就声称16KB兼容。

## 正式分发前的剩余事项

FFmpeg配置包含x264/x265、WebP、AV1、GnuTLS、字体及其他共享库，Python包也携带原生依赖。
它们各自保留版权和许可证；仅取得wrapper的Git仓库或Maven AAR不满足所有GPL组件的完整对应源码要求。
本次没有取得每个原生二进制准确对应的Termux提交、全部补丁和可复现构建记录，也没有假称已经提供三年书面源码要约。
正式分发必须补齐对应源码、构建脚本、许可证/NOTICE和必要安装信息，或自行从已审计固定源码重建原生包。
本版使用压缩+提取原生库的方式，不阻止用户修改源码重建应用；具体分发合规仍需根据实际产物确认。

没有包含Windows Qt/PySide/PyInstaller、aria2或Playwright。
不以原Windows第三方清单冒充Android依赖清单。

## Bundled yt-dlp engine update

The Android APK includes the official yt-dlp 2026.10.21 zipapp (Unlicense),
including yt-dlp-ejs (Unlicense), astring (MIT) and meriyah (ISC).
Provenance and SHA-256 are in extractor/src/main/assets/engine/PROVENANCE.txt.
Upstream source: https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.21
