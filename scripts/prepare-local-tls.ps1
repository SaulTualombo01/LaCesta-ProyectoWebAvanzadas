[CmdletBinding()]
param([string]$JdkPath = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$tlsDirectory = Join-Path $project 'data/tls'
$store = Join-Path $tlsDirectory 'localhost.p12'
$certificate = Join-Path $tlsDirectory 'localhost.cer'
$configuration = Join-Path $tlsDirectory 'local.properties'

$keytool = if ($JdkPath) { Join-Path $JdkPath 'bin/keytool.exe' } else {
    (Get-Command keytool.exe -ErrorAction SilentlyContinue).Source
}
if (-not $keytool -or -not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw 'Indica la carpeta del JDK usado en IntelliJ con -JdkPath.'
}

New-Item -ItemType Directory -Force -Path $tlsDirectory | Out-Null
if (-not (Test-Path -LiteralPath $configuration)) {
    if (Test-Path -LiteralPath $store) {
        throw 'Existe localhost.p12 pero falta su archivo local.properties.'
    }
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 24
        $random.GetBytes($bytes)
    } finally {
        $random.Dispose()
    }
    $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    [IO.File]::WriteAllText($configuration, "APP_TLS_KEY_STORE_PASSWORD=$password")
}
$stored = [regex]::Match([IO.File]::ReadAllText($configuration), '\AAPP_TLS_KEY_STORE_PASSWORD=([a-f0-9]{48})\s*\z')
if (-not $stored.Success) { throw 'local.properties no tiene el formato del certificado local de La Cesta.' }

$previous = $env:LA_CESTA_TLS_SETUP_PASSWORD
try {
    $env:LA_CESTA_TLS_SETUP_PASSWORD = $stored.Groups[1].Value
    if (-not (Test-Path -LiteralPath $store)) {
        & $keytool -genkeypair -alias localhost -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 -keystore $store -storepass:env LA_CESTA_TLS_SETUP_PASSWORD -dname 'CN=localhost, O=La Cesta Desarrollo' -ext 'SAN=DNS:localhost,IP:127.0.0.1' -ext 'EKU=serverAuth' -ext 'KU=digitalSignature,keyEncipherment' -ext 'BC=ca:false'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el certificado local.' }
    }
    if (-not (Test-Path -LiteralPath $certificate)) {
        & $keytool -exportcert -alias localhost -storetype PKCS12 -keystore $store -storepass:env LA_CESTA_TLS_SETUP_PASSWORD -file $certificate
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo exportar el certificado público.' }
    }
} finally {
    $env:LA_CESTA_TLS_SETUP_PASSWORD = $previous
}
Write-Output "Certificado público para localhost: $certificate"

