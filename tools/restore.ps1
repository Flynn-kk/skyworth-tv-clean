#requires -version 5
<#
.SYNOPSIS
  把电视还原成改造前的样子。
.DESCRIPTION
  只还原桌面/Home/禁用包这些本工具改过的东西。不碰你自己装的应用。
.EXAMPLE
  .\restore.ps1 -TvHost 192.168.1.50
.EXAMPLE
  .\restore.ps1 -TvHost 192.168.1.50 -Full     # 连被禁用的包一起恢复
#>
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [int]$Port = 5555,
    [switch]$Full,
    [switch]$UninstallGuard
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "tv.ps1") -TvHost $TvHost -Port $Port
Connect-Tv

# 这些是按本项目文档禁用过的包；-Full 时全部恢复
$OriginallyDisabled = @(
    "com.tianci.ad",
    "com.tianci.movieplatform",
    "com.coocaa.aigc.home",
    "com.coocaa.sport",
    "com.coocaa.guide",
    "com.coocaa.educate",
    "com.coocaa.tvmanager",
    "com.coocaa.app_browser",
    "com.coocaa.os.upgrade",
    "com.skyworth.airmouse.guide",
    "com.skyworth.performance.monitors",
    "com.tianci.appstore"
)

Write-Output "=== 1. 属性指回固件原来的目标 ==="
Invoke-Adb shell "setprop persist.service.homepage.pkg com.coocaa.aigc.home"
Invoke-Adb shell "setprop persist.service.homepage.cls com.coocaa.aigc.home.home.rootpage.HomeActivity"
Invoke-Adb shell "setprop persist.service.model.pkg com.skyworth.officehomepage"
Invoke-Adb shell "setprop persist.service.model.cls com.skyworth.officehomepage.view.HomepageActivity"
Write-Output ("   homepage.pkg = " + (Invoke-Adb shell "getprop persist.service.homepage.pkg").Trim())
Write-Output ("   model.pkg    = " + (Invoke-Adb shell "getprop persist.service.model.pkg").Trim())

Write-Output "=== 2. 重新启用影视主页 ==="
Invoke-Adb shell "pm enable --user 0 com.coocaa.aigc.home"
Write-Output "   已启用"

Write-Output "=== 3. 默认桌面改回创维办公主页 ==="
Invoke-Adb shell "cmd package set-home-activity com.skyworth.officehomepage/com.skyworth.officehomepage.view.HomepageActivity" | Out-Null
Write-Output "   已设置"

if ($UninstallGuard) {
    Write-Output "=== 4. 卸载守卫 ==="
    Invoke-Adb uninstall com.ccos.tv.homepage
}

if ($Full) {
    Write-Output "=== 5. 恢复全部被禁用的包（会包含开机广告和影视平台）==="
    foreach ($p in $OriginallyDisabled) {
        Invoke-Adb shell "pm enable --user 0 $p" 2>&1 | Out-Null
        Write-Output "   $p"
    }
}

Invoke-Adb shell "input keyevent KEYCODE_HOME" | Out-Null
Start-Sleep -Seconds 2
Write-Output ""
Write-Output "当前焦点：$((Invoke-Adb shell 'dumpsys window 2>/dev/null | grep mCurrentFocus').Trim())"
Write-Output "建议重启一次让固件重新读取： .\tv.ps1 -TvHost $TvHost -Reboot"
