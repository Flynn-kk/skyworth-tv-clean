# 07 · App 自己更新时报"未授权"

## 症状

某个影视 App 里点"检查更新"，弹：

> 电视未提供安装或授权入口，请通过系统文件管理器安装新版

## 原因

创维把 **"安装未知应用"这个设置页拿掉了**（`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 打不开）。
App 更新前会先问系统 `canRequestPackageInstalls()`，拿不到授权就自己放弃。

日志里能看到对应状态：

```
$ adb shell appops get com.example.app REQUEST_INSTALL_PACKAGES
No operations. Default mode: default        ← 就是没授权
```

## 修复

给这个 App 打开"安装未知应用"（等价于设置里那个开关）：

```powershell
adb shell appops set com.example.app REQUEST_INSTALL_PACKAGES allow
```

授权是**持久**的，重启不丢。

## 实测

红果 `com.hongguotv.nativeapp`，0.22.1+40 → 0.23.0+41：

1. 授权后点更新，不再报"未提供安装入口"
2. 变成申请存储权限（允许）
3. 自动下载 → 安装 → "安装成功"
4. 打开确认版本已变，观看记录都在

## 一个附带发现：安装器只认 content://

`com.android.packageinstaller` 的 `InstallStart` 过滤器是：

```
Action: VIEW / INSTALL_PACKAGE
Scheme: content          ← 只有 content，没有 file
Type:   application/vnd.android.package-archive
```

所以用 `file://` 的 APK 去发安装 Intent 是匹配不到的。这是 App 自己的实现问题，
不是电视的锅（但会让"更新失败"看起来像电视的问题）。

## 提醒

`REQUEST_INSTALL_PACKAGES` 等于允许这个 App 装任意 APK，**只给可信的 App 开**。
