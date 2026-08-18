param(
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$buildRoot = Join-Path $env:LOCALAPPDATA "BovedaWilson\build\app\outputs\apk"
$appApk = Join-Path $buildRoot "debug\app-debug.apk"
$testApk = Join-Path $buildRoot "androidTest\debug\app-debug-androidTest.apk"
$targetPackage = "cl.bovedawilson.app"
$runner = "cl.bovedawilson.app.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "cl.bovedawilson.app.ProcessDeathTest"
$prepareExtra = "processDeathPrepare"
$verifyExtra = "processDeathVerify"
$canary = "BW-PROCESS-DEATH-CANARY-FIXTURE"
$hierarchyPath = "/data/local/tmp/bw-process-death-hierarchy.xml"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available"
}

Push-Location $repositoryRoot
try {
    & $gradleWrapper :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-parallel
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $appApk)) { throw "App APK not found" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "Test APK not found" }

$adbArgs = @()
if ($DeviceSerial) { $adbArgs += @("-s", $DeviceSerial) }

function Get-WindowHierarchy {
    & adb @adbArgs shell uiautomator dump $hierarchyPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "UI hierarchy dump failed" }
    try {
        return ((& adb @adbArgs shell cat $hierarchyPath) | Out-String)
    } finally {
        & adb @adbArgs shell rm -f $hierarchyPath | Out-Null
    }
}

function Invoke-InstrumentationWithTimeout {
    param([int]$TimeoutSeconds = 90)
    $stdout = [System.IO.Path]::GetTempFileName()
    $stderr = [System.IO.Path]::GetTempFileName()
    $arguments = @($adbArgs) + @(
        "shell", "am", "instrument", "-w",
        "-e", "processDeathVerified", "true",
        "-e", "previousPid", $oldPid,
        "-e", "class", $testClass,
        $runner
    )
    try {
        $process = Start-Process -FilePath "adb" -ArgumentList $arguments -NoNewWindow -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            Stop-Process -Id $process.Id -Force
            throw "Process-death instrumentation timed out after $TimeoutSeconds seconds"
        }
        $text = ((Get-Content -LiteralPath $stdout -Raw) + (Get-Content -LiteralPath $stderr -Raw))
        Write-Output $text
        Write-Output "Instrumentation adb exit code: $($process.ExitCode)"
        if ($text -notmatch "OK \(1 test\)" -or $text -match "FAILURES!!!") {
            throw "Process-death instrumentation did not pass"
        }
    } finally {
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
}

& adb @adbArgs install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw "Test APK installation failed" }
& adb @adbArgs install -r -t $appApk
if ($LASTEXITCODE -ne 0) { throw "App installation failed" }

& adb @adbArgs shell am start -W -n "$targetPackage/.MainActivity" --ez $prepareExtra true
if ($LASTEXITCODE -ne 0) { throw "Initial activity launch failed" }

$preparedHierarchy = Get-WindowHierarchy
if ($preparedHierarchy -notmatch [regex]::Escape($canary)) {
    throw "The process-death canary was not visible before force-stop"
}

$oldPid = ((& adb @adbArgs shell pidof $targetPackage) | Out-String).Trim()
if (-not $oldPid) { throw "Target process did not start" }

& adb @adbArgs shell am force-stop $targetPackage
if ($LASTEXITCODE -ne 0) { throw "force-stop failed" }

Start-Sleep -Milliseconds 500
$remainingPid = ((& adb @adbArgs shell pidof $targetPackage) | Out-String).Trim()
if ($remainingPid) { throw "Target process survived force-stop" }

& adb @adbArgs shell am start -W -n "$targetPackage/.MainActivity" --ez $verifyExtra true
if ($LASTEXITCODE -ne 0) { throw "Post-death activity launch failed" }
$newPid = ((& adb @adbArgs shell pidof $targetPackage) | Out-String).Trim()
if (-not $newPid -or $newPid -eq $oldPid) { throw "Post-death process PID was not replaced" }
$restoredHierarchy = Get-WindowHierarchy
if ($restoredHierarchy -match [regex]::Escape($canary)) {
    throw "The process-death canary was restored after force-stop"
}
if ($restoredHierarchy -notmatch "Desbloquear") {
    throw "The post-death UI did not return to unlock"
}

Invoke-InstrumentationWithTimeout

Write-Output "Verified process death: PID $oldPid terminated, unlock was shown, and the canary was absent."
