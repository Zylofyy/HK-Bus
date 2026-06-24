$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$project = Join-Path $root "work\hkbus"
$outputs = Join-Path $root "outputs"
$versionFile = Join-Path $project "version.properties"

$props = @{}
Get-Content -LiteralPath $versionFile | ForEach-Object {
    if ($_ -match "^\s*([^#=]+)=(.*)$") { $props[$matches[1].Trim()] = $matches[2].Trim() }
}
$apkName = "hk-bus-arrivals-v$($props.versionName)-$($props.releaseId).apk"

$env:JAVA_HOME = (Resolve-Path (Join-Path $root "work\toolchain\jdk-17.0.19+10")).Path
$env:ANDROID_HOME = (Resolve-Path (Join-Path $root "work\toolchain\android-sdk")).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Push-Location $project
try {
    & "..\toolchain\gradle-8.7\bin\gradle.bat" assembleRelease --no-daemon
} finally {
    Pop-Location
}

$source = Join-Path $project "app\build\outputs\apk\release\$apkName"
$dest = Join-Path $outputs $apkName
if (Test-Path -LiteralPath $dest) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $dest = Join-Path $outputs ($apkName -replace "\.apk$", "-$stamp.apk")
}
Copy-Item -LiteralPath $source -Destination $dest
Get-FileHash -LiteralPath $dest -Algorithm SHA256