param(
    [string]$RepoRoot = ".",
    [string]$Repo = "xfashion44-jpg/livedate-webview-android",
    [string]$Workflow = "Android Debug APK",
    [string]$ArtifactName = "app-debug-apk",
    [string]$ArtifactsDir = ".\\artifacts",
    [switch]$SkipGitPull,
    [ValidateSet("throw", "fallback")]
    [string]$DownloadFailurePolicy = "fallback"
)

$ErrorActionPreference = "Stop"
$script:GhExe = $null

function Require-Cmd([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Resolve-GhExe() {
    $cmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) { return $cmd.Source }
    $candidates = @(
        "$env:ProgramFiles\\GitHub CLI\\gh.exe",
        "$env:LOCALAPPDATA\\Microsoft\\WinGet\\Links\\gh.exe"
    )
    foreach ($p in $candidates) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

function Invoke-GhJson([string[]]$GhArgs) {
    $jsonText = & $script:GhExe @GhArgs
    if ($LASTEXITCODE -ne 0) {
        throw "gh command failed: gh $($GhArgs -join ' ')"
    }
    if (-not $jsonText) {
        return $null
    }
    try {
        return ($jsonText | ConvertFrom-Json)
    } catch {
        $raw = ($jsonText | Out-String).Trim()
        throw "gh returned non-JSON output. Raw: $raw"
    }
}

Write-Host "[1/9] Checking required tools..."
Require-Cmd "git"
Require-Cmd "adb"
$script:GhExe = Resolve-GhExe
if (-not $script:GhExe) {
    throw "Required command not found: gh"
}

$resolvedRepoRoot = (Resolve-Path $RepoRoot).Path
Set-Location $resolvedRepoRoot

Write-Host "[2/9] Checking git status..."
$statusLines = @(git status --porcelain)
$statusForPull = @(
    $statusLines |
        Where-Object {
            $_ -notmatch '^\?\?\s+artifacts_test([\\/]|$)'
        }
)

$hasLocalChanges = $statusForPull.Count -gt 0
if ($hasLocalChanges) {
    Write-Host "Local changes detected."
    Write-Host ($statusForPull -join "`n")
}

if ($SkipGitPull) {
    Write-Host "[3/9] Skipping git pull by -SkipGitPull option."
} elseif ($hasLocalChanges) {
    Write-Host "[3/9] Skipping git pull due to local changes (download/install will continue)."
} else {
    Write-Host "[3/9] Pulling latest branch..."
    git pull
    if ($LASTEXITCODE -ne 0) {
        throw "git pull failed"
    }
}

Write-Host "[4/9] Checking GitHub authentication..."
& $script:GhExe auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "gh auth is required. Run 'gh auth login' first."
}

Write-Host "[5/9] Resolving latest successful workflow run..."
$runList = Invoke-GhJson @(
    "run", "list",
    "--repo", $Repo,
    "--workflow", $Workflow,
    "--status", "completed",
    "--limit", "50",
    "--json", "databaseId,headBranch,conclusion,status,displayTitle,createdAt"
)

if (-not $runList -or $runList.Count -eq 0) {
    throw "No completed workflow runs found for '$Workflow'."
}

$candidateRuns = @(
    $runList |
        Where-Object { $_.headBranch -eq "main" -and $_.status -eq "completed" -and $_.conclusion -eq "success" } |
        Select-Object -First 5
)

if ($candidateRuns.Count -eq 0) {
    throw "No matching runs found (branch=main, status=completed, conclusion=success)."
}

$selectedRunId = $candidateRuns[0].databaseId
Write-Host "  Selected RUN_ID: $selectedRunId"

Write-Host "[6/9] Downloading APK artifact..."
if (-not (Test-Path $ArtifactsDir)) {
    New-Item -ItemType Directory -Path $ArtifactsDir | Out-Null
}
Get-ChildItem -Path $ArtifactsDir -Recurse -Filter "*.apk" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

$downloadOk = $false
$finalRunId = $null
foreach ($r in $candidateRuns) {
    $runId = $r.databaseId
    if (-not $runId) { continue }
    Write-Host "  Trying RUN_ID: $runId"
    $runView = Invoke-GhJson @(
        "run", "view", "$runId",
        "--repo", $Repo,
        "--json", "artifacts"
    )
    $artifacts = @()
    if ($runView -and $runView.artifacts) {
        $artifacts = @($runView.artifacts)
    }
    if ($artifacts.Count -eq 0) {
        Write-Host "  Download FAIL (no artifacts in run)"
        continue
    }
    $selectedArtifact = $artifacts | Where-Object { $_.name -eq $ArtifactName } | Select-Object -First 1
    if (-not $selectedArtifact) {
        $selectedArtifact = $artifacts |
            Where-Object { $_.name -match "apk" -or $_.name -match "debug" } |
            Select-Object -First 1
    }
    if (-not $selectedArtifact) {
        Write-Host "  Download FAIL (no matching artifact name)"
        continue
    }
    $selectedArtifactName = $selectedArtifact.name
    Write-Host "    Artifact: $selectedArtifactName"
    & $script:GhExe run download "$runId" `
        --repo $Repo `
        --name $selectedArtifactName `
        --dir $ArtifactsDir
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Download OK"
        $downloadOk = $true
        $finalRunId = $runId
        $ArtifactName = $selectedArtifactName
        break
    }
    Write-Host "  Download FAIL"
}

if (-not $downloadOk) {
    if ($DownloadFailurePolicy -eq "throw") {
        throw "All download attempts failed (max 5 runs). Artifact '$ArtifactName' not available."
    }
    Write-Host "  Download failed for all candidates. Policy=fallback, switching to local APK."
}

Write-Host "[7/9] Verifying APK path..."
$apkPath = $null
if ($downloadOk) {
    $apkFile = Get-ChildItem -Path $ArtifactsDir -Recurse -Filter "*.apk" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $apkFile) {
        if ($DownloadFailurePolicy -eq "throw") {
            throw "Download succeeded but no APK file was found under '$ArtifactsDir'."
        }
        Write-Host "  Download artifact has no APK. Policy=fallback, switching to local APK."
    } else {
        $apkPath = $apkFile.FullName
    }
}
if (-not $apkPath) {
    $localApk = Join-Path $resolvedRepoRoot "app\\build\\outputs\\apk\\debug\\app-debug.apk"
    if (-not (Test-Path $localApk)) {
        throw "Fallback APK not found: $localApk"
    }
    $apkPath = $localApk
}
Write-Host "  APK: $apkPath"

Write-Host "[8/9] Installing APK to connected devices..."
$raw = adb devices
$deviceLines = $raw | Select-String -Pattern "device$" | ForEach-Object { $_.ToString() }
if (-not $deviceLines) {
    throw "No connected devices. Check USB debugging and authorization."
}

$failed = $false
foreach ($line in $deviceLines) {
    $serial = ($line -split "\s+")[0].Trim()
    if (-not $serial) { continue }
    Write-Host "  Installing to: $serial"
    $out = adb -s $serial install -r -d "$apkPath" 2>&1
    if ($out -match "Success") {
        Write-Host "    Install OK"
    } else {
        Write-Host "    Install FAIL"
        Write-Host ($out -join "`n")
        $failed = $true
    }
}

if ($failed) {
    throw "APK installation failed on one or more devices."
}

Write-Host "[9/9] Done"
Write-Host "  RepoRoot    : $resolvedRepoRoot"
Write-Host "  Run ID      : $finalRunId"
Write-Host "  Artifact    : $ArtifactName"
Write-Host "  Download OK : $downloadOk"
Write-Host "  Output Dir  : $ArtifactsDir"
Write-Host "  APK Path    : $apkPath"
