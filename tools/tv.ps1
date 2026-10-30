#requires -version 5
<#
.SYNOPSIS
  创维电视（酷开固件）清理与桌面改造工具箱。
.DESCRIPTION
  所有操作只用到 adb + setprop + pm，不刷机、不 root、不卸载系统应用，每一步都能撤回。
.EXAMPLE
  .\tv.ps1 -TvHost 192.168.1.50 -Status
.EXAMPLE
  .\tv.ps1 -TvHost 192.168.1.50 -Backup
.EXAMPLE
  .\tv.ps1 -TvHost 192.168.1.50 -Disable com.tianci.ad,com.coocaa.aigc.home
#>
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [int]$Port = 5555,
    [string]$Adb = "",

    [switch]$Status,
    [switch]$Backup,
    [string[]]$Disable,
    [string[]]$Enable,
    [switch]$Reboot,
    [switch]$TestHome,
    [switch]$Screenshot,
    [string]$ScreenshotPath = ".",
    [switch]$ShowGuard
)

$ErrorActionPreference = "Stop"

# ---- adb 定位 -------------------------------------------------------------
function Resolve-Adb {
    if ($Adb -and (Test-Path $Adb)) { return $Adb }
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\platform-tools\adb.exe",
        "D:\Program Files (x86)\pcsuite\adb_41\adb.exe",
        "D:\platform-tools\adb.exe"
    )
    foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
    throw "找不到 adb：装 platform-tools，或用 -Adb 指定路径。"
}

$script:AdbPath = Resolve-Adb
$script:Target = "${TvHost}:${Port}"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    & $script:AdbPath -s $script:Target @AdbArgs
}

function Connect-Tv {
    & $script:AdbPath connect $script:Target | Out-Null
    Start-Sleep -Milliseconds 400
    $state = (& $script:AdbPath -s $script:Target get-state 2>$null)
    if ("$state" -notmatch "device") { throw "连不上 $script:Target（电视开机了吗？ADB 开了吗？）" }
}

# ---- 状态 -----------------------------------------------------------------
function Get-TvStatus {
    Connect-Tv
    [pscustomobject]@{
        Model        = (Invoke-Adb shell "getprop ro.product.model").Trim()
        Android      = (Invoke-Adb shell "getprop ro.build.version.release").Trim()
        Firmware     = (Invoke-Adb shell "getprop ro.build.display.id").Trim()
        SELinux      = (Invoke-Adb shell "getenforce").Trim()
        Uptime       = (Invoke-Adb shell "cat /proc/uptime").Trim()
        HomepagePkg  = (Invoke-Adb shell "getprop persist.service.homepage.pkg").Trim()
        ModelPkg     = (Invoke-Adb shell "getprop persist.service.model.pkg").Trim()
        DefaultHome  = ((Invoke-Adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME") | Select-Object -Last 1)
        Focus        = ((Invoke-Adb shell "dumpsys window 2>/dev/null | grep mCurrentFocus") -replace '.*mCurrentFocus=', '').Trim()
        DisabledPkgs = (Invoke-Adb shell "pm list packages -d | wc -l").Trim()
        FreeMem      = (Invoke-Adb shell "free -m | grep Mem").Trim()
    }
}

# ---- 备份 -----------------------------------------------------------------
function Save-TvBackup {
    Connect-Tv
    $dir = "backup-$(Get-Date -Format yyyyMMdd-HHmmss)"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Invoke-Adb shell "pm list packages"      > "$dir/packages-all.txt"
    Invoke-Adb shell "pm list packages -d"   > "$dir/packages-disabled.txt"
    Invoke-Adb shell "getprop"               > "$dir/getprop.txt"
    Invoke-Adb shell "settings list global"  > "$dir/settings-global.txt"
    Invoke-Adb shell "settings list secure"  > "$dir/settings-secure.txt"
    Invoke-Adb shell "settings list system"  > "$dir/settings-system.txt"
    Invoke-Adb shell "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME" > "$dir/home-activities.txt"
    Write-Output "备份已存到 $dir"
    Write-Output "被禁用的包："
    Get-Content "$dir/packages-disabled.txt" | ForEach-Object { "  $_" }
}

# ---- 禁用 / 启用 -----------------------------------------------------------
function Disable-TvPackages {
    param([string[]]$Packages)
    Connect-Tv
    foreach ($p in $Packages) {
        Invoke-Adb shell "pm disable-user --user 0 $p" | Out-Null
        $state = (Invoke-Adb shell "pm list packages -d | grep -c '^package:$([regex]::Escape($p))$'").Trim()
        Write-Output ("{0,-40} {1}" -f $p, $(if ($state -eq "1") { "已禁用" } else { "没生效（检查包名）" }))
    }
}

function Enable-TvPackages {
    param([string[]]$Packages)
    Connect-Tv
    foreach ($p in $Packages) {
        Invoke-Adb shell "pm enable --user 0 $p" | Out-Null
        Write-Output ("{0,-40} 已启用" -f $p)
    }
}

# ---- 重启 / 验证 -----------------------------------------------------------
function Restart-Tv {
    param([int]$TimeoutSeconds = 180)
    Connect-Tv
    $t0 = Get-Date
    Invoke-Adb shell "svc power reboot" | Out-Null
    Write-Output "重启中…"
    Start-Sleep -Seconds 15
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        & $script:AdbPath connect $script:Target 2>&1 | Out-Null
        if ((& $script:AdbPath -s $script:Target get-state 2>$null) -match "device") {
            if ((& $script:AdbPath -s $script:Target shell "getprop sys.boot_completed" 2>$null).Trim() -eq "1") {
                $el = [int]((Get-Date) - $t0).TotalSeconds
                Write-Output "已开机，约 $el 秒"
                Start-Sleep -Seconds 6
                Write-Output "焦点：$((Invoke-Adb shell 'dumpsys window 2>/dev/null | grep mCurrentFocus').Trim())"
                return
            }
        }
        Start-Sleep -Seconds 4
    }
    throw "开机超时"
}

function Test-TvHome {
    param([string]$Expect = "")
    Connect-Tv
    Invoke-Adb shell "am start -n com.android.settings/.Settings" 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell "input keyevent KEYCODE_HOME" | Out-Null
    Start-Sleep -Seconds 3
    $f = (Invoke-Adb shell "dumpsys window 2>/dev/null | grep mCurrentFocus")
    if ($Expect -and "$f" -match [regex]::Escape($Expect)) {
        Write-Output "OK  Home 键回到了 $Expect"
    } else {
        Write-Output "当前焦点：$($f.Trim())"
    }
}

function Save-TvScreenshot {
    param([string]$Name = "tv")
    Connect-Tv
    $out = Join-Path (Resolve-Path $ScreenshotPath) "$Name.png"
    Invoke-Adb shell "screencap -p /sdcard/_shot.png" | Out-Null
    & $script:AdbPath -s $script:Target pull /sdcard/_shot.png $out | Out-Null
    Write-Output $out
}

# ---- 守卫状态 --------------------------------------------------------------
function Get-GuardState {
    Connect-Tv
    Invoke-Adb shell "dumpsys package com.ccos.tv.homepage | grep -m1 'User 0:'"
    Invoke-Adb shell "logcat -d 2>/dev/null | grep -i CcHomeGuard | tail -5"
}

# ---- 入口 ------------------------------------------------------------------
if ($Status)      { Get-TvStatus | Format-List }
if ($Backup)      { Save-TvBackup }
if ($Disable)     { Disable-TvPackages -Packages $Disable }
if ($Enable)      { Enable-TvPackages -Packages $Enable }
if ($Reboot)      { Restart-Tv }
if ($TestHome)    { Test-TvHome }
if ($Screenshot)  { Save-TvScreenshot -Name "tv" }
if ($ShowGuard)   { Get-GuardState }

if (-not ($Status -or $Backup -or $Disable -or $Enable -or $Reboot -or $TestHome -or $Screenshot -or $ShowGuard)) {
    Write-Output "用法示例："
    Write-Output "  .\tv.ps1 -TvHost 192.168.1.50 -Status"
    Write-Output "  .\tv.ps1 -TvHost 192.168.1.50 -Backup"
    Write-Output "  .\tv.ps1 -TvHost 192.168.1.50 -Disable com.tianci.ad,com.coocaa.aigc.home"
    Write-Output "  .\tv.ps1 -TvHost 192.168.1.50 -TestHome"
}
