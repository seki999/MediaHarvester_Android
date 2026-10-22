# 构建、签名与交付

## Debug

JDK17、SDK35，根目录运行 `./gradlew :app:assembleDebug -PtargetAbis=arm64-v8a`。
输出 `app/build/outputs/apk/debug/app-debug.apk`，使用Android标准开发签名；不要用于正式应用升级链。
本次实际输出的独立交付副本在 `artifacts/`，详情见validation.md。

## Release签名

不要提交keystore、密码或local.properties。备份你自己的签名密钥；APK更新必须使用同一密钥。
本工程没有生成或上传用户正式签名密钥。无签名环境变量时assembleRelease生成unsigned APK。

首次在本机自行创建密钥（交互输入密码）：

```powershell
keytool -genkeypair -keystore C:\private\mediaharvester-release.jks -alias mediaharvester -keyalg RSA -keysize 4096 -validity 10000
```

设置当前shell环境变量后构建：

```powershell
$env:MH_KEYSTORE = 'C:\private\mediaharvester-release.jks'
$env:MH_KEY_ALIAS = 'mediaharvester'
# MH_STORE_PASSWORD、MH_KEY_PASSWORD通过安全的本机凭据或CI secrets设置，不写入仓库。
.\gradlew.bat :app:assembleRelease -PtargetAbis=arm64-v8a
```

输出 `app/build/outputs/apk/release/`。使用SDK build-tools的`apksigner verify --verbose`验证正式签名。
未提供正式密钥，因此没有声称已生成正式签名APK。

## GitHub Actions

`.github/workflows/android.yml`提供：JDK17/SDK35的单元测试、Lint、Debug APK和报告；
Android15 x86_64模拟器的设备测试，保留测试报告。使用最小contents:read权限，无自动发布、上传密钥或推送代码。
工作流文件已提供；没有在远端触发或宣称GitHub运行成功。

## 发布前仍需完成

1. 物理arm64 Android14/15/16机型、长时锁屏/Doze/省电、断网和前台服务预算验收。
2. 重建/替换未对齐的WebP原生库并在16KB设备上验证，之后才能解除媒体运行时的4KB限制。
3. 归档准确匹配原生二进制的所有对应源码、补丁、构建脚本和许可证，不把wrapper源码误认为FFmpeg/Python全部对应源码。
4. 对依赖升级重跑传输、取消、数据库、DASH合并和保存验证。固定版本的yt-dlp会逐渐过时；本版以更新APK方式更新运行时。
5. 大文件、存储不足、撤销SAF权限及各网站真实资源的授权验收。不得添加DRM、验证码或登录绕过。

本工程不把未解决的原生源码合规和16KB问题标记为已解决；当前APK用于本机开发验收，未自动发布到商店或GitHub Releases。
