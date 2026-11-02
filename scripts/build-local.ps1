param(
    [string]$Studio = 'C:\Program Files\Android\Android Studio',
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$CacheRoot = "$env:USERPROFILE\.gradle\caches",
    [string]$Gradle = '',
    [string]$SshJar = '',
    [switch]$RunLint
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$Gradle) {
    $distribution = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-9.3.1-bin'
    $Gradle = Get-ChildItem -LiteralPath $distribution -Recurse -Filter gradle.bat | Select-Object -First 1 -ExpandProperty FullName
}
if (!$SshJar) { $SshJar = Join-Path $Studio 'lib\module-intellij.libraries.sshj.jar' }
foreach ($required in @($Gradle, $SshJar, (Join-Path $Studio 'jbr\bin\java.exe'), (Join-Path $Sdk 'platforms\android-36\android.jar'))) {
    if (!$required -or !(Test-Path -LiteralPath $required)) { throw "Missing local build dependency: $required" }
}
$previousJava = $env:JAVA_HOME
$previousGradle = $env:GRADLE_USER_HOME
$previousReadCache = $env:GRADLE_RO_DEP_CACHE
$previousAndroid = $env:ANDROID_HOME
try {
    $env:JAVA_HOME = Join-Path $Studio 'jbr'
    $env:ANDROID_HOME = $Sdk
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user'
    $env:GRADLE_RO_DEP_CACHE = $CacheRoot
    Push-Location -LiteralPath $projectRoot
    try {
        $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest')
        if ($RunLint) { $tasks += ':app:lintDebug' }
        & $Gradle --offline "-PofflineSshJar=$SshJar" @tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
        if (!$RunLint) { Write-Warning 'Android Lint is not included in this offline build; its tool package is not cached. Run gradlew :app:lintDebug when Maven access is available.' }
        Write-Host "APK: $(Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')"
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousGradle
    $env:GRADLE_RO_DEP_CACHE = $previousReadCache
    $env:ANDROID_HOME = $previousAndroid
}
