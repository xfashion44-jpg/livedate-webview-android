param(
    [string]$RepoRoot = ".",
    [string]$ApkPath = ".\artifacts\app-debug-apk\app-debug.apk",
    [string]$ArtifactsDir = ".\artifacts"
)

$ErrorActionPreference = "Stop"

function Require-Cmd([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Write-Host "[1/6] Checking required tools..."
Require-Cmd "git"
Require-Cmd "gh"
Require-Cmd "adb"

$resolvedRepoRoot = (Resolve-Path $RepoRoot).Path
Set-Location $resolvedRepoRoot

Write-Host "[2/6] Download latest APK via pull_and_fetch_apk.ps1..."
powershell -ExecutionPolicy Bypass -File ".\scripts\pull_and_fetch_apk.ps1" -RepoRoot .

Write-Host "[3/6] Validate APK path..."
if (-not (Test-Path $ApkPath)) {
    # 자동 탐색: artifacts 아래 최신 apk 찾기
    $found = Get-ChildItem -Path $ArtifactsDir -Recurse -Filter "*.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($found) {
        $ApkPath = $found.FullName
        Write-Host "  APK auto-detected: $ApkPath"
    } else {
        throw "APK not found: $ApkPath"
    }
}

Write-Host "[4/6] List connected devices..."
$raw = adb devices
$deviceLines = $raw | Select-String -Pattern "device$" | ForEach-Object { $_.ToString() }

if (-not $deviceLines) {
    throw "No connected devices. Check USB debugging/authorization. (adb devices)"
}

$serials = @()
foreach ($line in $deviceLines) {
    $serial = ($line -split "\s+")[0].Trim()
    if ($serial) { $serials += $serial }
}

Write-Host "  Devices: $($serials -join ', ')"

Write-Host "[5/6] Install APK to devices (-r -d)..."
$results = @()

foreach ($s in $serials) {
    Write-Host "  Installing to: $s"
    $out = adb -s $s install -r -d "$ApkPath" 2>&1
    $ok = ($out -match "Success")
    $results += [pscustomobject]@{
        serial  = $s
        success = $ok
        output  = ($out -join "`n")
    }

    if ($ok) {
        Write-Host "    ✅ Success"
    } else {
        Write-Host "    ❌ Failed"
        Write-Host $out
    }
}

Write-Host ""
Write-Host "[6/6] Summary..."
foreach ($r in $results) {
    $mark = if ($r.success) { "Success" } else { "Fail" }
    Write-Host " - $($r.serial): $mark"
}

if ($results.Where({ -not $_.success }).Count -gt 0) {
    Write-Host ""
    Write-Host "Hint:"
    Write-Host " - unauthorized: allow USB debugging on device"
    Write-Host " - UPDATE_INCOMPATIBLE: signature mismatch (need uninstall once)"
    exit 1
}

Write-Host ""
Write-Host "Done:"
Write-Host "  RepoRoot : $resolvedRepoRoot"
Write-Host "  APK      : $ApkPath"
Write-Host "  Devices  : $($serials -join ', ')"
