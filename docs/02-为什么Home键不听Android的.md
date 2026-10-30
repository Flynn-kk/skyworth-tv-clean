# 02 · 为什么 Home 键不听 Android 的

这是整个改造里最反直觉的一点，也是所有其他工作绕不开的前提。

## 现象

把第三方桌面装好、`cmd package set-home-activity` 设成默认：

```
$ adb shell cmd package set-home-activity com.example.launcher/.MainActivity
Success
$ adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
com.example.launcher/.MainActivity          ← Android 认为它已经是桌面了
```

然后按遥控器的 **Home 键** —— 回到的却是创维自己的页面。

## 原因：固件有两套自己的"桌面"

`/system/ccos/etc/bootintent.xml` 里，创维定义了一串"开机/回桌面该拉谁"的规则：

```xml
<home name="modelhome" relation="and" propcomp="persist.service.model.pkg/persist.service.model.cls">
    <prop name="persist.service.model.pkg" type="exists"/>
    <prop name="persist.service.model.cls" type="exists"/>
</home>
```

实测出来的分工：

| 动作 | 固件读的属性 | 我们观察到 |
|---|---|---|
| **开机** | `persist.service.model.pkg` / `.cls` | 匹配则直启；不匹配则落到 framework 的 HOME |
| **按 Home 键** | `persist.service.homepage.pkg` / `.cls` | 直接启动，**完全不看** framework 的 HOME |

## 更麻烦的：属性会被改回去

设好属性、重启，再看：

```
# 重启前
persist.service.model.pkg     = com.coocaa.tvhome
persist.service.model.cls     = com.gathin.tvhome.MainActivity
# 重启后
persist.service.model.pkg     = （空）
persist.service.model.cls     = （空）

persist.service.homepage.pkg  = com.coocaa.aigc.home     ← 被写回影视主页
```

固件在开机过程中会把这两个属性重写。所以「改一次属性」在重启后必然失效。

## 实测验证方法

改属性、从别的 App 按 Home，看焦点落在谁身上：

```powershell
adb shell setprop persist.service.homepage.pkg  com.example.launcher
adb shell setprop persist.service.homepage.cls  com.example.launcher.MainActivity
adb shell am start -n com.example.video/.MainActivity   # 先进别的 App
adb shell input keyevent KEYCODE_HOME
adb shell "dumpsys window | grep mCurrentFocus"
```

属性指对时立刻生效，指回原值立刻复原 —— 这个实验证明的就是"固件在读这个属性"。

## 由此推出的方案

1. **开机**：`set-home-activity` 设好默认桌面 + 写入 `model.*`，双保险。
   `set-home-activity` 是系统设置，重启不丢。
2. **Home 键**：只能每次开机把 `homepage.*` 重写一遍 —— 于是有了[守卫应用](03-固件守卫.md)。
3. **不要禁用影视主页然后指望它兜底**：一旦属性指着被禁用的包，Home 键会直接失效（实测：
   焦点不动，什么也不发生），而不是回落到 framework 桌面。

第 3 条是血的教训：最省事的做法（直接 `pm disable-user` 影视主页）恰恰会把手柄弄死。
