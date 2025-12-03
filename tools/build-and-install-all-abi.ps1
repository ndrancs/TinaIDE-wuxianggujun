Param(
    [ValidateSet("debug", "release")]
    [string]$Variant = "debug"
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..")

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$WarnOnly
    )
    Write-Host "Executing Gradle task: $Task" -ForegroundColor DarkCyan
    & ./gradlew $Task
    if ($LASTEXITCODE -ne 0) {
        if ($WarnOnly) {
            Write-Host "Gradle task failed (${Task}) but script will continue. See Gradle output above for details." -ForegroundColor Yellow
        } else {
            Write-Host "Gradle task failed: $Task" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    }
}

function Ensure-HostFlatc {
    $setupScript = Join-Path $scriptRoot "setup-flatc.ps1"
    if (-not (Test-Path $setupScript)) {
        Write-Host "setup-flatc.ps1 not found at $setupScript" -ForegroundColor Yellow
        return
    }

    $flatcDir = Join-Path $repoRoot "external/flatbuffers-prebuilt"
    $hostDescription = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
    Write-Host "Ensuring host flatc is available..." -ForegroundColor Cyan
    try {
        & $setupScript | Out-Null
    } catch {
        Write-Host "Failed to bootstrap flatc via setup script" -ForegroundColor Red
        throw
    }
}

Ensure-HostFlatc

function Clean-NativeOutputs {
    Write-Host "Cleaning previous native build outputs..." -ForegroundColor Cyan
    $cleanTasks = @(
        ":app:externalNativeBuildCleanDebug",
        ":app:externalNativeBuildCleanRelease"
    )
    foreach ($task in $cleanTasks) {
        Invoke-GradleTask -Task $task -WarnOnly
    }
    $pathsToRemove = @(
        (Join-Path $repoRoot "app/.cxx"),
        (Join-Path $repoRoot "app/build/intermediates/cmake")
    )
    foreach ($path in $pathsToRemove) {
        if (Test-Path $path) {
            Write-Host "Removing $path" -ForegroundColor DarkGray
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
        }
    }
}

Clean-NativeOutputs

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
Invoke-GradleTask -Task $gradleTask

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
