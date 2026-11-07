<#
.SYNOPSIS
    Pruebas de Instalar-K-Snag.exe sobre dist/ (CI y release.yml).

.DESCRIPTION
    En un PC con Smart App Control activo el .exe sin firmar no abre: por eso estas pruebas corren en los
    runners de GitHub. Instala siempre en carpetas de $env:RUNNER_TEMP (o %TEMP%) con --no-registry: no toca
    la instalación real ni el registro.

      1. --help termina con 0 y lista las opciones; una opción desconocida termina con 1.
      2. Instalación nueva con --local --quiet: deja runtime, launch.pyw y current, y --selftest pasa.
      3. Reinstalación encima (actualizar una carpeta existente) con --console: muestra los pasos.
      4. Un --sha256 equivocado termina con 1 y no deja la carpeta.
      5. Descarga real: si el Release v<versión> ya existe, baja su bundle y lo instala con el hash publicado.
      6. La ventana abre y sigue abierta (sin opciones); se cierra a los pocos segundos.

    El .exe es una app de ventana: se espera con Start-Process -Wait para leer el código de salida.

.PARAMETER Dist
    Carpeta con Instalar-K-Snag.exe y kd-bundle-<versión>.zip. Por defecto, dist.

.PARAMETER SkipDownload
    No hace la prueba 5 (descarga real).
#>
[CmdletBinding()]
param(
    [string]$Dist = 'dist',
    [switch]$SkipDownload
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$Dist = (Resolve-Path $Dist).Path
$Exe = Join-Path $Dist 'Instalar-K-Snag.exe'
$Bundle = (Get-ChildItem -LiteralPath $Dist -Filter 'kd-bundle-*.zip' | Select-Object -First 1).FullName
if (-not (Test-Path -LiteralPath $Exe)) { throw "Falta $Exe" }
if (-not $Bundle) { throw "Falta kd-bundle-*.zip en $Dist" }
$Version = [regex]::Match((Split-Path -Leaf $Bundle), '^kd-bundle-(.+)\.zip$').Groups[1].Value
$Temp = $env:RUNNER_TEMP
if (-not $Temp) { $Temp = [IO.Path]::GetTempPath() }
$Work = Join-Path $Temp ('kd-exe-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
[void](New-Item -ItemType Directory -Path $Work)
$Log = Join-Path ([IO.Path]::GetTempPath()) 'K-Snag-instalacion.log'

function Invoke-Exe([string[]]$Arguments, [string]$Name) {
    $out = Join-Path $Work ($Name + '.out.txt')
    $err = Join-Path $Work ($Name + '.err.txt')
    $quoted = @($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $p = Start-Process -FilePath $Exe -ArgumentList $quoted -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput $out -RedirectStandardError $err
    $text = ''
    if (Test-Path -LiteralPath $out) { $text = [IO.File]::ReadAllText($out) }
    if (Test-Path -LiteralPath $err) { $text += [IO.File]::ReadAllText($err) }
    Write-Host ("--- {0} (código {1})" -f $Name, $p.ExitCode)
    Write-Host $text
    return @{ Code = $p.ExitCode; Text = $text }
}

function Assert-Installed([string]$Root) {
    foreach ($item in 'runtime\python.exe', 'runtime\pythonw.exe', 'launch.pyw', 'current', ('app\' + $Version)) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $item))) { throw "La instalación en $Root no dejó $item" }
    }
    $cur = ([IO.File]::ReadAllText((Join-Path $Root 'current'))).Trim()
    if ($cur -ne $Version) { throw "current = $cur, se esperaba $Version" }
    & (Join-Path $Root 'runtime\python.exe') (Join-Path $Root 'launch.pyw') --selftest
    if ($LASTEXITCODE -ne 0) { throw "--selftest terminó con código $LASTEXITCODE" }
}

try {
    # 1. Ayuda y opciones
    $r = Invoke-Exe @('--help') 'help'
    if ($r.Code -ne 0) { throw "--help terminó con código $($r.Code)" }
    foreach ($opt in '--local', '--sha256', '--root', '--no-registry', '--quiet', '--console', '--launch') {
        if (-not $r.Text.Contains($opt)) { throw "--help no menciona $opt" }
    }
    $r = Invoke-Exe @('--quiet', '--no-existe') 'unknown'
    if ($r.Code -ne 1) { throw "Una opción desconocida terminó con código $($r.Code) (se esperaba 1)" }

    # 2. Instalación nueva
    $root = Join-Path $Work 'nueva\K-Snag'
    $r = Invoke-Exe @('--local', $Bundle, '--root', $root, '--no-registry', '--quiet') 'install'
    if ($r.Code -ne 0) { throw "La instalación terminó con código $($r.Code). Registro: $(Get-Content -Raw $Log -ErrorAction SilentlyContinue)" }
    Assert-Installed $root

    # 3. Reinstalación encima, en modo consola
    $r = Invoke-Exe @('--local', $Bundle, '--root', $root, '--no-registry', '--console', '--no-launch') 'reinstall'
    if ($r.Code -ne 0) { throw "La reinstalación terminó con código $($r.Code)" }
    if (-not $r.Text.Contains('[8/8]')) { throw 'El modo consola no mostró los pasos' }
    Assert-Installed $root
    $leftovers = @(Get-ChildItem -LiteralPath $root -Directory -Filter '*.old-*') + @(Get-ChildItem -LiteralPath (Split-Path $root) -Directory -Filter '*.staging-*')
    if ($leftovers.Count -gt 0) { throw "Quedaron carpetas temporales: $($leftovers.FullName -join ', ')" }

    # 4. Hash equivocado
    $badRoot = Join-Path $Work 'hash\K-Snag'
    $r = Invoke-Exe @('--local', $Bundle, '--root', $badRoot, '--no-registry', '--quiet', '--sha256', ('0' * 64)) 'badhash'
    if ($r.Code -ne 1) { throw "Con un hash equivocado terminó con código $($r.Code) (se esperaba 1)" }
    if (Test-Path -LiteralPath $badRoot) { throw 'Con un hash equivocado quedó la carpeta de instalación' }

    # 5. Descarga real del Release publicado (si existe), con su hash
    if (-not $SkipDownload) {
        $sumsUrl = "https://github.com/Kasaco223/K-Snag/releases/download/v$Version/SHA256SUMS.txt"
        $sums = $null
        try { $sums = (Invoke-WebRequest -Uri $sumsUrl -UseBasicParsing).Content } catch { Write-Host "Sin Release v$Version publicado: se salta la descarga real ($($_.Exception.Message))" }
        if ($sums -is [byte[]]) { $sums = [Text.Encoding]::UTF8.GetString($sums) }
        if ($sums) {
            $m = [regex]::Match($sums, '(?m)^([0-9a-f]{64})\s+\*?kd-bundle-' + [regex]::Escape($Version) + '\.zip\s*$')
            if (-not $m.Success) { throw "SHA256SUMS.txt de v$Version no lista el bundle" }
            $dlRoot = Join-Path $Work 'descarga\K-Snag'
            $r = Invoke-Exe @('--root', $dlRoot, '--no-registry', '--console', '--no-launch', '--sha256', $m.Groups[1].Value) 'download'
            if ($r.Code -ne 0) { throw "La instalación con descarga terminó con código $($r.Code)" }
            if (-not $r.Text.Contains('%')) { throw 'La descarga no mostró progreso' }
            Assert-Installed $dlRoot
        }
    }

    # 6. La ventana abre (sin instalar nada) y se cierra
    $p = Start-Process -FilePath $Exe -ArgumentList @('--root', (Join-Path $Work 'ventana\K-Snag'), '--no-registry') -PassThru
    Start-Sleep -Seconds 6
    if ($p.HasExited) { throw "La ventana se cerró sola con código $($p.ExitCode). Registro: $(Get-Content -Raw $Log -ErrorAction SilentlyContinue)" }
    if ($p.MainWindowHandle -eq [IntPtr]::Zero) { $p.Refresh() }
    Write-Host ("--- ventana: abierta ('{0}')" -f $p.MainWindowTitle)
    Stop-Process -Id $p.Id -Force
    Write-Host 'Instalar-K-Snag.exe: todas las pruebas pasaron.'
} finally {
    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
}
