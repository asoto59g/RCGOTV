$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$publicDocuments = Join-Path $env:PUBLIC "Documents"
$buildRoot = Join-Path $publicDocuments ("RCGOTV-build-" + [guid]::NewGuid().ToString("N"))
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome) -or
    -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Configura JAVA_HOME para apuntar a un JDK 17 antes de compilar."
}

$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) {
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path (Join-Path $sdk "platforms\android-36"))) {
    throw "No se encontró Android SDK Platform 36 en '$sdk'."
}

New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
try {
    $excludedBuild = Join-Path $projectRoot "app\build"
    $excludedGradle = Join-Path $projectRoot ".gradle"
    $excludedGit = Join-Path $projectRoot ".git"
    & robocopy.exe $projectRoot $buildRoot /E /XD $excludedBuild $excludedGradle $excludedGit /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -ge 8) {
        throw "No se pudo preparar la copia temporal (robocopy $LASTEXITCODE)."
    }

    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path
    Set-Location $buildRoot
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la compilación del APK debug."
    }

    $builtApk = Join-Path $buildRoot "app\build\outputs\apk\debug\app-debug.apk"
    $buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $buildTools) {
        throw "No se encontró Android Build Tools en '$sdk'."
    }
    $aapt = Join-Path $buildTools.FullName "aapt.exe"
    $metadata = & $aapt dump badging $builtApk
    if ($LASTEXITCODE -ne 0 -or
        -not ($metadata -match "package: name='com\.abcgeomag\.rcgotv'") -or
        -not ($metadata -match "application-label:'RCGOTV'")) {
        throw "El APK no contiene el paquete y nombre RCGOTV esperados."
    }

    $deliverableApk = Join-Path $projectRoot "RCGOTV-debug.apk"
    Copy-Item -LiteralPath $builtApk -Destination $deliverableApk -Force
    Write-Output "APK creado: $deliverableApk"
}
finally {
    Set-Location $projectRoot
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
