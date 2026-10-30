# 三个 App

| 目录 | 包名 | 作用 | 大小 |
|---|---|---|---|
| `wallpaper/` | `com.ccos.wallpaper` | 手机扫码传壁纸 + 改桌面图标 | ~250 KB |
| `cast/` | `com.ccos.cast` | 投屏入口壳，转去系统的爱投屏 | ~7 KB |
| `guard/` | `com.ccos.tv.homepage` | 固件守卫，开机重写 Home 属性 | ~8 KB |

三个都是普通应用，不需要 root。

## 准备工具链

不需要完整 Android Studio，只要这几样（约 250 MB）：

| 东西 | 用途 | 从哪拿 |
|---|---|---|
| JDK 17 | javac / jarsigner | <https://repo.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip> |
| `android-30.jar` | 编译用的 bootclasspath | <https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar> |
| `r8.jar` | 把 class 转成 dex（d8） | <https://maven.aliyun.com/repository/google/com/android/tools/r8/8.5.35/r8-8.5.35.jar> |
| `aapt2` | 编资源、打 APK | <https://maven.aliyun.com/repository/google/com/android/tools/build/aapt2/7.4.2-8841542/aapt2-7.4.2-8841542-windows.jar> |
| `zxing-core.jar` | 生成二维码（只有 wallpaper 用） | <https://maven.aliyun.com/repository/public/com/google/zxing/core/3.5.3/core-3.5.3.jar> |

放在同一个目录，例如 `%LOCALAPPDATA%\skyworth-tv-build`。

## 编译

### wallpaper（壁纸 App）

```powershell
cd app\wallpaper
.\build-wallpaper.ps1 -Toolchain "$env:LOCALAPPDATA\skyworth-tv-build"
```

脚本会依次跑：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 把 `classes.dex` 塞进 APK → `jarsigner`。

### cast（投屏壳）

没有构建脚本，四步手工：

```powershell
$aapt2 compile --dir res -o build\res.zip
$aapt2 link -o build\base.apk -I android-30.jar --manifest AndroidManifest.xml -R build\res.zip `
            --min-sdk-version 26 --target-sdk-version 29 --auto-add-overlay
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath android-30.jar -d build\classes src\com\ccos\cast\*.java
d8 --release --min-api 26 --lib android-30.jar --output build\dex build\classes\com\ccos\cast\*.class
# 再把 build\dex\classes.dex 打进 base.apk，最后 jarsigner 签名
```

### guard（守卫）

```powershell
cd app\guard
.\build-guard.ps1
```

守卫的做法特殊：**复用已有 APK 的清单，只替换 `classes.dex`**。因为它依赖清单里已经声明好的
`BootReceiver` / `LaunchActivity` / `Service`，而我们没有 aapt 处理二进制清单的便利。

`build-guard.ps1` 里会去找一个模板 APK（`assets/guard-template.apk`），替换 dex 后重新签名。

## 签名说明

三个都用同一套**自签名**（`jarsigner`，v1 方案）：

- `targetSdk` 设成 **29**，这样 Android 11 接受 v1-only 签名（targetSdk ≥ 30 强制要 v2）
- 正式发布请自己生成 keystore：`keytool -genkeypair -keystore signing.keystore -alias guard -keyalg RSA -validity 10000`

自签名只影响"能不能覆盖升级"，不影响功能。

## 装到电视后必须做的授权

```powershell
# 壁纸 App：写桌面主题（ThemeProvider 要求签名级权限，只能 adb 授）
adb shell pm grant com.ccos.wallpaper android.permission.WRITE_SECURE_SETTINGS

# 壁纸 App：后台启动桌面（Android 10+ 默认禁止）
adb shell appops set com.ccos.wallpaper SYSTEM_ALERT_WINDOW allow

# 任意需要自更新的 App
adb shell appops set <包名> REQUEST_INSTALL_PACKAGES allow
```

## 一个坑：装完要清"停止态"

`adb install` 之后应用处于 stopped 状态，**开机广播送不到**。守卫这类靠广播启动的应用必须
先启动一次它的任意组件：

```powershell
adb shell am start -n com.ccos.tv.homepage/.LaunchActivity
```
