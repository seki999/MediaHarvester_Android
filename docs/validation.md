# 实际验证记录

日期：2026-10-23（Asia/Tokyo）。宿主Windows，JDK17.0.20.1，SDK35，Gradle8.9，AGP8.7.3，Kotlin2.0.21。
Android设备：本机启动的 `commander_review_35`，Android15/API35、x86_64、4KB页面模拟器。
原Windows仓库只读克隆的 `git status --porcelain` 为空，没有修改源项目。

## 本次真实结果

| 项目 | 结果 |
|---|---|
| domain测试 | 6通过：多URL、非法URL、状态转换/恢复、文件名和损坏摘要 |
| downloader测试 | 15通过：完整HTTP、严格Range、非法Content-Range、验证器变化、弱ETag、200/416、安全重下、断网恢复、Socket取消、HTML/空文件拒绝、元数据提交崩溃窗口、私网拒绝正确返回错误 |
| extractor单元测试 | 3通过：src/srcset/lazy/picture/OG/背景、去重、尺寸 |
| app设备集成测试 | 9通过：真实PNG批量/去重/重名、损坏拒绝、取消、发布日志恢复、FFmpeg合并及MP3/M4A/Opus转换、真实DASH经yt-dlp格式解析/双轨下载与合并、Keystore Cookie范围/加密、通知service action暂停/取消、Home+锁屏后台WAV下载 |
| Room设备测试 | 2通过：关闭数据库重开恢复验证器和字节数，取消拒绝迟到完成 |
| 合计 | **35通过，0失败，0错误，0跳过**；详见test-results.json |
| Android Lint | 0错误、8警告，警告为有更新依赖的版本提示；没有用忽略错误掩盖失败 |
| Debug和Release构建 | arm64-only Debug APK和unsigned Release APK实际构建成功 |
| APK签名 | arm64 Debug通过apksigner verify，v2签名、1个开发签名者；Release未签名 |
| 启动/视觉检查 | x86_64 APK实际安装并启动首页，截图人工检查；见screenshots/home.png |
| GitHub Actions | 工作流已提供；未在GitHub远端触发，不声称已远端通过 |

主要复现命令：

```powershell
.\gradlew.bat :domain:test :downloader:test :extractor:testDebugUnitTest :app:connectedDebugAndroidTest :database:connectedDebugAndroidTest :app:lintDebug
.\gradlew.bat :extractor:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug -PtargetAbis=arm64-v8a
```

本地详细日志在被git忽略的 `artifacts/verification.log`、`arm64-build.log`；
JUnit/Android报告在各模块build/reports，精简结果已保留docs/test-results.json。
测试中的HTTP资源均来自本地受控服务器，视频/音频由APK自带FFmpeg生成；没有使用私人账号、DRM或验证码。
锁屏样例是短时WAV传输，不能替代真实机型长时Doze验收。
最后的格式标签文字修正后重跑了extractor单元测试、编译与Lint；完整设备测试覆盖同一下载逻辑。

## 实际交付产物

产物位于工程根 `artifacts/`；完整SHA256也在 `artifacts/SHA256SUMS.txt`。

| 文件 | 大小 | SHA256 |
|---|---|---|
| MediaHarvester-0.1.0-arm64-debug.apk | 79.04 MiB | `9764f72bbea8bee13cef3cc4822d2f0c509fc2b5d2c10d73ad7e0a69abdf1bd6` |
| MediaHarvester-0.1.0-arm64-release-unsigned.apk | 73.73 MiB | `de680bffe4dfe49112c7f9f8f799582872cfa826bb862c64fa7b7cdbd9002c49` |
| MediaHarvester-0.1.0-universal-debug.apk | 129.79 MiB | `1a403bf03e366cb5f3bd550cf6fbfb0339b13bfdf053934bb78c6d6113941496` |

默认多ABI开发构建较大，arm64专用版优先用于真机。未签名Release不能直接安装，正式签名见RELEASE.md。

## 原生运行时与16KB

模拟器实际读取：yt-dlp **2025.11.12**，FFmpeg **7.1.1**；FFmpeg启用GPL/version3，
详见native-runtime.txt。没有用库README中的旧Python版本描述冒充实际运行时版本。
当前固定yt-dlp版本较旧，逐站兼容不能保证；升级以重新构建APK方式进行。

本次arm64 Debug APK及嵌套ZIP检查了 **255** 个ELF条目，
其中 **5** 个WebP相关库未满足16KB加载段对齐。清单见native-audit.json。
因此媒体运行时检测到大于4KB页面会明确停用；直接HTTP与静态图片下载使用Kotlin/Android能力继续提供。
ELF检查不是运行验证：arm64物理设备、Android14/16及16KB设备尚未执行验收。

## 尚未验证或未完成

- Android14/16、物理arm64、平板、长时Doze/厂商电池管理、真实系统FGS超时预算触发。
- 大型真实网站播放列表/动态签名、受授权真实登录网站、真实Cookie与长期yt-dlp更新兼容。
- SAF全部提供者、权限撤销/存储满/外部介质中断、外部播放器/分享应用的逐项人工验收。
- 物理断电或完整进程杀死压力：已测试数据库重新打开与持久化中断状态，但不是断电实验。
- 图片选择最大声明候选，不包含坏的最大候选自动退回次高候选；未知尺寸不伪造。
- MediaStore/SAF分配成功到Room记录URI之间还有跨存储崩溃残留窗口。
- yt-dlp子进程尚未实现Windows等价的公网认证代理；合并阶段子进程取消仍需真实大任务压力验证。
- 原生FFmpeg/Python/共享库完整对应源码与全部NOTICE溯源仍待完成，不宣称已具备正式分发的完整合规包。
