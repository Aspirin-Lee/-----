# 实验日程 LabDay
Android 个人实验安排与记录原型。代码直接通过 GitHub API 初始化，构建在 GitHub-hosted Actions 执行，无需本机 Android SDK。

## 当前功能
今天／明天／后天安排，新建和编辑实验，设计和结果，本机保存，系统语音输入，图片选择并复制到本机私有目录。
先保存实验，再重新打开记录添加图片。所有数据初始为空。

## 安装
在 Actions 中打开 Android APK 工作流，等待构建成功，下载 LabDay-debug-apk 并解压安装 APK。这是调试版，不是正式发布包。
构建使用 JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3 和 SDK 35；CI 下载 Gradle，因此未提交 wrapper 二进制。

## 已知边界
Notion 同步、图片云端上传、提醒、导出和安全备份尚未实现。
记录目前存于本机 SharedPreferences，仅用于原型验证；卸载将丢失数据，请勿保存唯一原始实验记录。
语音需要手机提供识别服务，可能联网，不能保证所有安卓手机支持。
尚需真机验证权限、语音服务、图片显示和进程回收。
不要在此公开仓库上传实验内容或密钥。详细路线见 docs/ROADMAP.md。
