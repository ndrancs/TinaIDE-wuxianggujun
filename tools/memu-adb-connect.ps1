[CmdletBinding()]
param(
    [string]$MemuDir = "D:\Program Files\Microvirt\MEmu",
    [int]$InstanceIndex = 0,
    [int]$BasePort = 21503,
    [switch]$OpenShell
)

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }

$adbPath = Join-Path $MemuDir "adb.exe"
if (-not (Test-Path $adbPath -PathType Leaf)) {
    Write-Err "找不到 ADB：$adbPath"
    exit 1
}

if ($InstanceIndex -lt 0) {
    Write-Err "InstanceIndex 不能为负数"
    exit 1
}

$port = $BasePort + ($InstanceIndex * 2)
$serial = "127.0.0.1:$port"

Write-Info "使用 ADB：$adbPath"
Write-Info "目标实例：index=$InstanceIndex，ADB 地址=$serial"

& $adbPath kill-server | Out-Null
& $adbPath start-server | Out-Null

$existing = & $adbPath devices
$alreadyConnected = $existing -match [regex]::Escape($serial)

if (-not $alreadyConnected) {
    Write-Info "尝试连接 $serial ..."
    $connectOutput = & $adbPath connect $serial 2>&1
    if ($LASTEXITCODE -ne 0 -or ($connectOutput -notmatch "connected to" -and $connectOutput -notmatch "already connected to")) {
        Write-Err "连接失败：$connectOutput"
        exit 2
    }
    Write-Info ($connectOutput.Trim())
} else {
    Write-Info "已存在连接，无需重复连接。"
}

Write-Info "当前设备列表："
& $adbPath devices

if ($OpenShell) {
    Write-Info "打开 shell，会话结束后自动退出。"
    & $adbPath -s $serial shell
}
