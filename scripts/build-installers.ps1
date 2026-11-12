$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$distDir = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Write-Host "=== 1. Building Windows Desktop Setup (laptop guard.setup.exe) ==="
dotnet test (Join-Path $root 'laptop-app\LaptopGuard.sln')
dotnet publish (Join-Path $root 'laptop-app\LaptopGuard\LaptopGuard.csproj') `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:DebugType=none -p:DebugSymbols=false `
    -o (Join-Path $root 'laptop-app\LaptopGuard\bin\publish-win-x64')

$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $iscc) {
    throw "Inno Setup 6 (ISCC.exe) not found."
}
& $iscc (Join-Path $root 'laptop-app\installer\LaptopGuard.iss')

Write-Host "=== 2. Building Android Mobile Setup (laptop guard mobile.apk) ==="
$androidBase = Join-Path $env:LOCALAPPDATA 'Android'
if (-not $env:JAVA_HOME -and (Test-Path "$androidBase\jdk-17")) {
    $env:JAVA_HOME = "$androidBase\jdk-17"
}
if (-not $env:ANDROID_HOME -and (Test-Path "$androidBase\Sdk")) {
    $env:ANDROID_HOME = "$androidBase\Sdk"
    $env:ANDROID_SDK_ROOT = "$androidBase\Sdk"
}
if ($env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

$androidDir = Join-Path $root 'android-app'
Push-Location $androidDir
try {
    & .\gradlew.bat testDebugUnitTest assembleDebug
}
finally {
    Pop-Location
}

$apkSrc = Join-Path $androidDir 'app\build\outputs\apk\debug\app-debug.apk'
$apkDst = Join-Path $distDir 'laptop guard mobile.apk'
Copy-Item $apkSrc $apkDst -Force

Write-Host "=== Build Complete ==="
Get-ChildItem $distDir | Select-Object Name, @{N='SizeMB';E={[math]::Round($_.Length/1MB,2)}}

