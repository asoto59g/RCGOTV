param(
    [string]$VersionName = "",
    [int]$VersionCode = 0
)

$ErrorActionPreference = "Stop"

if ($VersionCode -lt 0) {
    throw "VersionCode must be zero (use the project default) or a positive integer."
}

$projectRoot = $PSScriptRoot
$publicDocuments = Join-Path $env:PUBLIC "Documents"
$buildRoot = Join-Path $publicDocuments ("RCGOTV-release-" + [guid]::NewGuid().ToString("N"))
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

$keystore = Read-Host "Ruta completa del keystore de subida (guárdalo fuera del proyecto/OneDrive)"
$alias = Read-Host "Alias de la clave de subida"
if ([string]::IsNullOrWhiteSpace($keystore) -or
    -not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
    throw "No se encontró el archivo keystore indicado."
}
if ([string]::IsNullOrWhiteSpace($alias)) {
    throw "El alias de la clave no puede estar vacío."
}

$storePassword = Read-Host "Contraseña del keystore" -AsSecureString
$keyPassword = Read-Host "Contraseña de la clave" -AsSecureString
$storePointer = [IntPtr]::Zero
$keyPointer = [IntPtr]::Zero
$environmentNames = @(
    "RCGOTV_UPLOAD_STORE_FILE",
    "RCGOTV_UPLOAD_STORE_PASSWORD",
    "RCGOTV_UPLOAD_KEY_ALIAS",
    "RCGOTV_UPLOAD_KEY_PASSWORD",
    "JAVA_HOME",
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "Path"
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $storePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePassword)
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPassword)
    $env:RCGOTV_UPLOAD_STORE_FILE = (Resolve-Path -LiteralPath $keystore).Path
    $env:RCGOTV_UPLOAD_KEY_ALIAS = $alias
    $env:RCGOTV_UPLOAD_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePointer)
    $env:RCGOTV_UPLOAD_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path

    New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
    $excludedBuild = Join-Path $projectRoot "app\build"
    $excludedGradle = Join-Path $projectRoot ".gradle"
    $excludedGit = Join-Path $projectRoot ".git"
    & robocopy.exe $projectRoot $buildRoot /E /XD $excludedBuild $excludedGradle $excludedGit /XF *.jks *.keystore /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -ge 8) {
        throw "No se pudo preparar la copia temporal (robocopy $LASTEXITCODE)."
    }

    Set-Location $buildRoot
    $gradleArguments = @(":app:testDebugUnitTest", ":app:bundleRelease", "--no-daemon")
    if ($VersionCode -gt 0) {
        $gradleArguments += "-PrcgotvVersionCode=$VersionCode"
    }
    if (-not [string]::IsNullOrWhiteSpace($VersionName)) {
        $gradleArguments += "-PrcgotvVersionName=$VersionName"
    }
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Falló la prueba o la generación del Android App Bundle."
    }

    $builtBundle = Join-Path $buildRoot "app\build\outputs\bundle\release\app-release.aab"
    if (-not (Test-Path -LiteralPath $builtBundle -PathType Leaf)) {
        throw "No se encontró el Android App Bundle firmado esperado."
    }
    $jarsigner = Join-Path $javaHome "bin\jarsigner.exe"
    $verification = & $jarsigner -verify $builtBundle 2>&1
    if ($LASTEXITCODE -ne 0 -or ($verification -join "`n") -notmatch "jar verified") {
        throw "No se pudo verificar la firma del Android App Bundle."
    }

    $deliverableBundle = Join-Path $projectRoot "RCGOTV-release.aab"
    Copy-Item -LiteralPath $builtBundle -Destination $deliverableBundle -Force
    Write-Output "Android App Bundle firmado creado: $deliverableBundle"
}
finally {
    if ($storePointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePointer)
    }
    if ($keyPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], "Process")
    }
    if (Test-Path -LiteralPath $buildRoot) {
        Set-Location $projectRoot
        Remove-Item -LiteralPath $buildRoot -Recurse -Force
    }
}
