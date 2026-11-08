# Builds the "repo" folder (index.min.json + apk + icon) from the APKs you just built.
# Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File .\scripts\make-repo.ps1
param([string]$BuildType = "debug")

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repoDir = Join-Path $root "repo"

# ---- find aapt2 (ships with the Android SDK) ----
$sdk = $null
$localProps = Join-Path $root "local.properties"
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$aapt2 = Get-ChildItem -Path (Join-Path $sdk "build-tools") -Filter "aapt2.exe" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt2) {
    throw "Could not find aapt2.exe under '$sdk\build-tools'. Open Android Studio > SDK Manager > SDK Tools and install 'Android SDK Build-Tools'."
}

# ---- find the built APKs ----
$apks = Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter "*.apk" |
    Where-Object { $_.FullName -match "\\build\\outputs\\apk\\$BuildType\\" }
if (-not $apks) {
    throw "No $BuildType APKs found. Build first: .\gradlew.bat :src:all:hxtnhentai:assembleDebug :src:all:hxthitomi:assembleDebug"
}

# ---- fresh repo folder ----
if (Test-Path $repoDir) { Remove-Item -Recurse -Force $repoDir }
New-Item -ItemType Directory -Path (Join-Path $repoDir "apk") | Out-Null
New-Item -ItemType Directory -Path (Join-Path $repoDir "icon") | Out-Null

$entries = @()
foreach ($apk in $apks) {
    $badging = & $aapt2.FullName dump badging $apk.FullName
    $text = $badging -join "`n"

    if ($text -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
        throw "Could not read package info from $($apk.Name)"
    }
    $pkg = $Matches[1]; $code = [long]$Matches[2]; $ver = $Matches[3]

    $label = $pkg
    if ($text -match "application-label:'([^']*)'") { $label = $Matches[1] }

    $nsfw = 1
    if ($text -match "name='tachiyomi\.extension\.nsfw' value='(\d)'") { $nsfw = [int]$Matches[1] }

    $lang = ($pkg.Split('.'))[4]

    $apkName = "$pkg-v$ver.apk"
    Copy-Item $apk.FullName (Join-Path $repoDir "apk\$apkName")

    $moduleDir = $apk.FullName -replace '\\build\\outputs\\.*$', ''
    $icon = Join-Path $moduleDir "res\mipmap-xxxhdpi\ic_launcher.png"
    if (Test-Path $icon) { Copy-Item $icon (Join-Path $repoDir "icon\$pkg.png") }

    $entries += [ordered]@{
        name    = $label
        pkg     = $pkg
        apk     = $apkName
        lang    = $lang
        code    = $code
        version = $ver
        nsfw    = $nsfw
        sources = @()
    }
    Write-Host "Added $label  (v$ver, code $code)"
}

# UTF-8 *without* BOM: the app expects the file to start with "["
$json = ConvertTo-Json -InputObject @($entries) -Depth 5 -Compress
[System.IO.File]::WriteAllText((Join-Path $repoDir "index.min.json"), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "Done. The 'repo' folder is ready: $repoDir"
Write-Host "Next: commit and push (GitHub Desktop), then use this link in the app:"
Write-Host "  https://raw.githubusercontent.com/<YOUR-USERNAME>/<YOUR-REPO>/main/repo/index.min.json"
