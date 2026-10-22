# 架构与恢复约束

## 模块边界

app：Application、Activity、Android 前台服务、通知、分享入口。
ui：Compose 四页、响应式网格与最大内容宽度、ViewModel；不运行下载逻辑。
domain：不可变媒体模型、真实格式、DownloadEngine / MediaExtractor接口、状态机、URL与文件名规则。
data：DownloadCoordinator、队列调度、网络策略、设置、加密Cookie。
database：Room实体、DAO、模式导出、Flow观察、比较并更新状态。
extractor：静态HTML、Android yt-dlp、Android FFmpeg进程包装与媒体轨道验证。
downloader：纯JVM OkHttp传输、连接取消、严格Range验证，可独立测试。
storage：MediaStore pending文件和SAF tree、校验写入、重名编号、删除应用自己创建的失败输出。

ViewModel 只提交解析、入队和控制动作。DownloadService拥有工作协程生命周期；
队列存储在Room，UI退出不拥有任务生命周期。最多4个任务，当前默认2个。

## 状态机

QUEUED → DOWNLOADING → [MERGING] → VERIFYING → SAVING → COMPLETED。
活动任务可 → PAUSED / CANCELLED / FAILED；下载可 → WAITING_NETWORK。
PAUSED / FAILED / WAITING_NETWORK → QUEUED。COMPLETED/CANCELLED不可被迟到回调复活。
正常更新用 SQL `WHERE state=expected` CAS；进度只写 DOWNLOADING。
控制操作先持久化状态、取消网络/本地进程、等待工作结束，再清理自己的未完成输出。
Room恢复将活动任务、排队、网络等待转为PAUSED，禁止开机后无限重启下载。
数据库模式不使用 destructiveMigration，未来升级必须显式迁移。

## HTTP续传

临时文件位于应用私有 files/tasks/<UUID>/payload.part。
必须有强ETag、已知总长度和有效本地长度，才发Range + If-Range。
使用 identity 编码。206需精确匹配起点、终点、总长度、响应长度和ETag；
200表示重新写入；非法206、416、验证器变化先清空并只重试一次无Range请求。
多次非法响应失败，不拼接未知数据。已知长度的正文必须完整，空文件、HTML错误页不能完成。
取消会关闭Call，并把Socket异常归一为协程取消；同步临时文件，保留已写字节。
IO失败最多4次尝试（1/2/4秒退避）；网络不可用或Wi-Fi不满足转为网络等待。
返回网络后在仍存活的服务中恢复；服务已被杀则下次打开手动继续。
HLS/DASH不使用HTTP单文件续传，重新解析原网页并重启碎片下载。

## 保存、去重与崩溃窗口

传输结束后：图片至少验证可解码尺寸；音视频验证可读元数据、时长和需要的轨道。
FFmpeg退出为0且合并结果文件存在之后才进入VERIFYING；合并视频还要求音轨存在。
计算SHA-256，图片已存在时重新读目标校验，才能复用记录。
发布锁串行处理去重与目标分配，防止两个相同图片任务同时发布。

MediaStore先创建IS_PENDING条目，Room持久化SAVING+URI+摘要，复制并重新读目标核对摘要，
清除IS_PENDING，Room原子标记完成。重启时若SAVING目标摘要相同则补提交；否则删除自己未完成输出并暂停。
SAF没有MediaStore的pending可见性语义，中断可能留下暂时可见的半成品，恢复会删除再保存。

跨提供者没有分布式事务：MediaStore/SAF分配成功至Room日志写入之间若被强杀，可能留下未关联条目。
本版没有声称消除这个窗口；不扫描或删除用户未知文件。正式生产可扩展 allocation journal / provider reconciliation。
目前MediaStore分配到Room日志的残留窗口未全消除，SAF没有等价的原子pending机制。
完整性校验表示传输和保存字节相同，不是网站真实性、密码学签名验证或全片解码无损证明。

## Android后台

minSdk29，compile/target35。Android14声明FOREGROUND_SERVICE及DATA_SYNC权限和类型，
用户从可见Activity启动服务；通知含暂停全部/取消全部。API35合并/转换时动态附加mediaProcessing类型和相应权限。通知被拒绝不要求绕过系统限制。
服务START_NOT_STICKY，无BOOT_COMPLETED后台启动，无无限WorkManager重启。
Android15按系统dataSync预算运行（6小时/24小时），onTimeout及时stopForeground/stopSelf，保留Room状态。
有界6小时PARTIAL_WAKE_LOCK在服务结束释放；不申请忽略电池优化，不能保证Doze/厂商省电下持续联网。
Android16没有从前台服务启动WorkManager/JobScheduler来规避配额。
长时间离线等待仍会占系统预算，超过预算由系统中止；重新打开应用可继续。
模拟器锁屏测试不等于物理设备长时Doze、OEM省电或Android16验收。

## Cookies与隐私

只接收用户选择的Netscape文件以及明确授权的HTTPS网站。最多1MiB，按域筛选，
本版存一个授权网站；Android Keystore AES-GCM加密，文件置于noBackupFilesDir，禁用备份。
仅运行所需时导出私有缓存临时文件，finally删除；重启清理遗留临时Cookie。
不记录Cookie值、Authorization或原始yt-dlp错误；网站错误保留分类，直接错误过滤URL。
不自动获取浏览器Cookie，不绕过DRM、验证码、地区或访问授权，不暴力重试403/429。
应用内最近URL/任务URL是本地记录；带签名URL可能敏感，用户可清理历史。未进行数据库整盘加密。
OkHttp实施公网DNS/实际连接检查；yt-dlp原生网络隔离没有等价代理；该差异明确保留在移植矩阵中。
