# 后续机型验收

自动设备测试：`./gradlew :app:connectedDebugAndroidTest :database:connectedDebugAndroidTest`。
测试使用目标应用私有注入的本地HTTP客户端；测试后关闭服务器、删除自己发布的样例文件、恢复屏幕唤醒。
真实设备测试前确认目标序列号，勿把模拟器测试记录当作手机验收。

## Android14/15/16 与 arm64

记录型号、系统版本、`adb shell getconf PAGE_SIZE`、APK SHA256、网络与电池设置。
安装arm64 APK，分享HTTP公开样例、解析实际格式，正常完成并用播放器确认音视频。
手机竖屏/横屏、平板、浅/深/系统主题、中/日/英分别操作；打开/分享给系统查看器。
16KB设备应保留直接文件与静态图片能力，并明确报告媒体运行时不支持，不能崩溃或假称合并成功。

## 后台与锁屏

使用有权访问的大型HTTP样例，下载后Home、锁屏5/30/120分钟；记录通知、进度、完成文件摘要。
启用系统省电/厂商策略、Doze后观察；允许系统暂停，不应丢任务或标成成功。
通知暂停应关闭传输并保留分片；继续时核对强ETag和Range；取消不应留下完成记录。
断开Wi-Fi再恢复，启用“仅Wi-Fi”后切换移动网络，确认停止传输，回Wi-Fi后恢复。
用系统强停：不会承诺继续；重新打开手动恢复。系统杀进程/重启后记录应仍在。

## Android15超时（专用测试设备）

参考 https://developer.android.com/develop/background-work/services/fgs/timeout 。
先记录`device_config get activity_manager data_sync_fgs_timeout_duration`原值。
在专用模拟器临时设较短预算，启动用户下载后Home，检查onTimeout及时停止服务，任务可恢复，日志无FGS超时崩溃。
测试后还原原值；不要无条件覆盖他人的设备配置。
当前没有把系统真实超时触发实验标记为已通过。

## 文件与恢复

SAF选择Downloads以外的受授权目录；下载中撤销权限、拔掉外部介质、模拟磁盘满；应失败并保留任务。
同名不同内容必须独立文件；相同图片应只引用已确认完整的文件。
在DOWNLOADING/MERGING/SAVING阶段终止进程，再开应用核对状态、目标校验与重试。
跨MediaStore/Room分配日志的残留窗口见ARCHITECTURE.md，尚未声称完全消除。
