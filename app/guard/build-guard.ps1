#requires -version 5
# 重新编译 com.ccos.tv.homepage 守卫（只换 classes.dex，清单和资源沿用 assets\guard-template.apk）。
# 为什么这样做：本机没有 aapt，改不了二进制清单；模板 APK 已经声明好 BootReceiver / BootWatchService /
# LaunchActivity，我们只替换里面的代码，再用同一个 keystore 签名，就能覆盖安装。

$ErrorActionPreference = "Stop"
$skillDir = $PSScriptRoot
$buildDir = Join-Path $env:LOCALAPPDATA "skyworth-tv-build"
$jdkDir = Join-Path $buildDir "jdk-17.0.2"

$jdkUrl = "https://repo.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip"
$androidJarUrl = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar"
$r8Url = "https://maven.aliyun.com/repository/google/com/android/tools/r8/8.5.35/r8-8.5.35.jar"

function Ensure-Toolchain {
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $jdkZip = Join-Path $buildDir "jdk17.zip"
    if (-not (Test-Path (Join-Path $jdkDir "bin\javac.exe"))) {
        if (-not (Test-Path $jdkZip)) {
            Write-Output "downloading JDK 17 (~178 MB)..."
            Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing -TimeoutSec 600
        }
        Write-Output "extracting JDK..."
        Expand-Archive -Path $jdkZip -DestinationPath $buildDir -Force
    }
    $androidJar = Join-Path $buildDir "android-30.jar"
    if (-not (Test-Path $androidJar)) {
        Write-Output "downloading android.jar..."
        Invoke-WebRequest -Uri $androidJarUrl -OutFile $androidJar -UseBasicParsing -TimeoutSec 300
    }
    $r8 = Join-Path $buildDir "r8.jar"
    if (-not (Test-Path $r8)) {
        Write-Output "downloading r8/d8..."
        Invoke-WebRequest -Uri $r8Url -OutFile $r8 -UseBasicParsing -TimeoutSec 300
    }
    [pscustomobject]@{ Javac = Join-Path $jdkDir "bin\javac.exe"; Java = Join-Path $jdkDir "bin\java.exe"
                       Keytool = Join-Path $jdkDir "bin\keytool.exe"; Jarsigner = Join-Path $jdkDir "bin\jarsigner.exe"
                       AndroidJar = $androidJar; R8 = $r8 }
}

function Build-Guard {
    $tc = Ensure-Toolchain
    $work = Join-Path $buildDir "work"
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Join-Path $work "classes"), (Join-Path $work "dex") | Out-Null

    $srcs = Get-ChildItem (Join-Path $PSScriptRoot "src\com\ccos\tv\homepage\*.java") | ForEach-Object { $_.FullName }
    & $tc.Javac -nowarn -source 8 -target 8 -bootclasspath $tc.AndroidJar -d (Join-Path $work "classes") $srcs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

    $classFiles = Get-ChildItem (Join-Path $work "classes\com\ccos\tv\homepage\*.class") | ForEach-Object { $_.FullName }
    & $tc.Java -cp $tc.R8 com.android.tools.r8.D8 --release --min-api 21 --lib $tc.AndroidJar --output (Join-Path $work "dex") $classFiles 2>&1
    if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

    $template = Join-Path $skillDir "assets\guard-template.apk"
    $unsigned = Join-Path $work "guard-unsigned.apk"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zin = [System.IO.Compression.ZipFile]::OpenRead($template)
    $zout = [System.IO.Compression.ZipFile]::Open($unsigned, "Create")
    foreach ($e in $zin.Entries) {
        if ($e.FullName -eq "classes.dex") { continue }
        if ($e.FullName -like "META-INF/*") { continue }
        $ne = $zout.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $i = $e.Open(); $o = $ne.Open(); $i.CopyTo($o); $o.Dispose(); $i.Dispose()
    }
    $se = $zout.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $fs = [System.IO.File]::OpenRead((Join-Path $work "dex\classes.dex"))
    $ds = $se.Open(); $fs.CopyTo($ds); $ds.Dispose(); $fs.Dispose()
    $zout.Dispose(); $zin.Dispose()

    $out = Join-Path $skillDir "assets\guard.apk"
    Copy-Item $unsigned $out -Force
    $ks = Join-Path $skillDir "assets\guard.keystore"
    & $tc.Jarsigner -keystore $ks -storepass guardpass -keypass guardpass -sigalg SHA256withRSA -digestalg SHA-256 $out guard 2>&1 | Out-Null
    Write-Output "built: $out ($((Get-Item $out).Length) bytes)"
    Write-Output "装到电视：Install-TvApk -Path '$out' -Reinstall（签名不同要先 adb uninstall com.ccos.tv.homepage）"
    return $out
}
