$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

Write-Host "==> Running :core:test and :windows:installDist..."
& .\gradlew.bat :core:test :windows:installDist --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$InputDir = Join-Path $RepoRoot "windows\build\install\windows\lib"
if (-not (Test-Path (Join-Path $InputDir "windows.jar"))) {
    throw "Expected windows.jar under $InputDir"
}

$JpackageCandidates = @()
if ($env:JAVA_HOME) {
    $JpackageCandidates += (Join-Path $env:JAVA_HOME "bin\jpackage.exe")
}
$JpackageCandidates += (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName "bin\jpackage.exe" })
$JpackageCandidates += (Get-ChildItem "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-21*" -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName "bin\jpackage.exe" })
$JpackageCandidates += (Get-ChildItem "C:\Program Files\Android\Android Studio\jbr\bin\jpackage.exe" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

$JpackageExe = $JpackageCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $JpackageExe) {
    throw "jpackage.exe (JDK 21+) not found. Set JAVA_HOME to Eclipse Adoptium JDK 21."
}
Write-Host "==> Using jpackage: $JpackageExe"

$JpackageDest = Join-Path $RepoRoot "build\jpackage"
if (Test-Path $JpackageDest) {
    Remove-Item -Recurse -Force $JpackageDest
}
New-Item -ItemType Directory -Force -Path $JpackageDest | Out-Null

$Modules = "java.desktop,java.logging,java.management,java.naming,java.net.http,jdk.crypto.ec,jdk.unsupported,jdk.accessibility,java.security.jgss"

& $JpackageExe `
    --type app-image `
    --name MAX `
    --input $InputDir `
    --main-jar windows.jar `
    --main-class com.maxaiagent.windows.MainKt `
    --dest $JpackageDest `
    --add-modules $Modules

if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$DistDir = Join-Path $RepoRoot "dist"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$ZipPath = Join-Path $DistDir "MAX-Windows-x64.zip"
if (Test-Path $ZipPath) {
    Remove-Item -Force $ZipPath
}

Write-Host "==> Archiving $JpackageDest\MAX -> $ZipPath"
& tar.exe -a -cf $ZipPath -C $JpackageDest MAX
if ($LASTEXITCODE -ne 0) {
    throw "tar.exe failed with exit code $LASTEXITCODE"
}

Write-Host "==> Done! Portable app: $ZipPath"
