#requires -version 5
# 编译电视壁纸 App（com.ccos.wallpaper）。
# 依赖：JDK17 + android-30.jar + r8.jar + aapt2 + zxing-core.jar，默认从 toolchain 目录取。
param(
    [string]$Toolchain = "",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$app = $PSScriptRoot
if (-not $Toolchain) {
    $Toolchain = "$env:LOCALAPPDATA\skyworth-tv-build"
}
if (-not $OutDir) { $OutDir = Join-Path $app "build" }

$jd = Join-Path $Toolchain "jdk-17.0.2\bin"
$aapt2 = Join-Path $Toolchain "aapt2\aapt2.exe"
$aj = Join-Path $Toolchain "android-30.jar"
$r8 = Join-Path $Toolchain "r8.jar"
$zxing = Join-Path $Toolchain "zxing-core.jar"
$ks = Join-Path $Toolchain "signing.keystore"

foreach ($p in @("$jd\javac.exe", $aapt2, $aj, $r8, $zxing)) {
    if (-not (Test-Path $p)) { throw "缺少构建依赖：$p" }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$work = Join-Path $OutDir "_work"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$work\classes", "$work\dex" | Out-Null

Write-Output "1/5 aapt2 compile"
& $aapt2 compile --dir (Join-Path $app "res") -o "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Output "2/5 aapt2 link"
& $aapt2 link -o "$work\base.apk" -I $aj --manifest (Join-Path $app "AndroidManifest.xml") `
    -R "$work\res.zip" --min-sdk-version 26 --target-sdk-version 29 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Output "3/5 javac"
$srcs = Get-ChildItem (Join-Path $app "src\com\ccos\wallpaper\*.java") | ForEach-Object { $_.FullName }
& "$jd\javac.exe" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $aj -cp $zxing -d "$work\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Output "4/5 d8"
$classes = Get-ChildItem "$work\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$jd\java.exe" -cp $r8 com.android.tools.r8.D8 --release --min-api 26 --lib $aj --output "$work\dex" $classes $zxing
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Output "5/5 打包并签名"
Copy-Item "$work\base.apk" "$work\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path "$work\unsigned.apk"), 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$fs = [System.IO.File]::OpenRead((Resolve-Path "$work\dex\classes.dex"))
$os = $entry.Open(); $fs.CopyTo($os); $os.Dispose(); $fs.Dispose()
$zip.Dispose()

$apk = Join-Path $OutDir "tvwallpaper.apk"
Copy-Item "$work\unsigned.apk" $apk -Force
& "$jd\jarsigner.exe" -keystore $ks -storepass guardpass -keypass guardpass `
    -sigalg SHA256withRSA -digestalg SHA-256 $apk guard 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Output ("done: {0} ({1:N0} bytes)" -f $apk, (Get-Item $apk).Length)
return $apk
