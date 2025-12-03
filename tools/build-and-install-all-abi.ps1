Param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug"
)

$ErrorActionPreference = "Stop"

$adbDir = "D:\Programs\Android\Sdk\platform-tools"
$adbExe = Join-Path $adbDir "adb.exe"
if (-not (Test-Path $adbExe)) {
    Write-Host "adb not found at $adbExe" -ForegroundColor Red
    exit 1
}
if (-not ($env:Path -split ";" | ForEach-Object { $_.Trim() } | Where-Object { $_ -eq $adbDir })) {
    $env:Path = "$adbDir;$env:Path"
}

function Get-GradleTask {
    param([string]$variant)
    $capitalized = $variant.Substring(0,1).ToUpper() + $variant.Substring(1)
    return "assemble${capitalized}AllAbi"
}

$gradleTask = Get-GradleTask -variant $Variant
Write-Host "Running Gradle task: $gradleTask" -ForegroundColor Cyan
./gradlew $gradleTask | Out-Host

$apkName = "app-$Variant.apk"
$apkPath = Join-Path "app/build/outputs/apk/$Variant" $apkName
if (-not (Test-Path $apkPath)) {
    Write-Host "APK not found: $apkPath" -ForegroundColor Red
    exit 1
}

Write-Host "Installing $apkPath via adb..." -ForegroundColor Cyan
& $adbExe install -r $apkPath | Out-Host

Write-Host "Launching com.wuxianggujun.tinaide/.ui.ProjectManagerActivity" -ForegroundColor Cyan
& $adbExe shell am start -n com.wuxianggujun.tinaide/.ui.ProjectManagerActivity | Out-Host

Write-Host "Build and install completed." -ForegroundColor Green
