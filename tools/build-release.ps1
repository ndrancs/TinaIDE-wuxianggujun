# TinaIDE Release Build Script
# Usage: .\tools\build-release.ps1 [-Clean]

param(
    [switch]$Clean
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  TinaIDE Release Build" -ForegroundColor Cyan  
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Get script directory
$scriptPath = $PSScriptRoot
if ([string]::IsNullOrEmpty($scriptPath)) {
    $scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Definition
}
if ([string]::IsNullOrEmpty($scriptPath)) {
    $scriptPath = Get-Location
}

# Get project root directory
$repoRoot = Resolve-Path (Join-Path $scriptPath "..")
Write-Host "Project root: $repoRoot" -ForegroundColor DarkGray

# Change to project root
Set-Location $repoRoot

# Check signing config
$keystoreProps = Join-Path $repoRoot "keystore.properties"
if (-not (Test-Path $keystoreProps)) {
    Write-Host "Error: keystore.properties not found" -ForegroundColor Red
    exit 1
}
Write-Host "Signing config: OK" -ForegroundColor Green

# Check keystore file
$keystoreFile = Join-Path $repoRoot "keystore\release.jks"
if (-not (Test-Path $keystoreFile)) {
    Write-Host "Error: keystore\release.jks not found" -ForegroundColor Red
    exit 1
}
Write-Host "Keystore file: OK" -ForegroundColor Green
Write-Host ""

# Optional: clean build cache
if ($Clean) {
    Write-Host "Cleaning build cache..." -ForegroundColor Yellow
    .\gradlew.bat clean
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Clean failed" -ForegroundColor Red
        exit $LASTEXITCODE
    }
    Write-Host ""
}

# Build Release APK
Write-Host "Building Release APK..." -ForegroundColor Cyan
Write-Host "  - Code shrinking enabled (R8)" -ForegroundColor DarkGray
Write-Host "  - Resource shrinking enabled" -ForegroundColor DarkGray
Write-Host "  - Target ABI: arm64-v8a, x86_64" -ForegroundColor DarkGray
Write-Host ""

.\gradlew.bat assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Build failed! Check errors above." -ForegroundColor Red
    exit $LASTEXITCODE
}

# Check output
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if (Test-Path $apkPath) {
    $apkInfo = Get-Item $apkPath
    $sizeMB = [math]::Round($apkInfo.Length / 1MB, 2)
    
    Write-Host ""
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  Build Successful!" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "APK path: $apkPath" -ForegroundColor White
    Write-Host "APK size: $sizeMB MB" -ForegroundColor White
    Write-Host ""
    
    # Open output directory
    $outputDir = Split-Path $apkPath -Parent
    $response = Read-Host "Open output directory? (Y/N)"
    if ($response -eq "Y" -or $response -eq "y") {
        Start-Process explorer.exe -ArgumentList $outputDir
    }
} else {
    Write-Host ""
    Write-Host "Warning: APK file not found, check build log" -ForegroundColor Yellow
}
