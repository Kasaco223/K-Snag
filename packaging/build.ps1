<#
.SYNOPSIS
    Construye la distribución de K-Snag: kd-bundle, kd-app, los instaladores .cmd y .exe, latest.json y SHA256SUMS.txt.

.DESCRIPTION
    La app no lleva ningún .exe propio: Smart App Control bloquea los .exe nuevos sin firma. El bundle lleva el
    Python embeddable oficial (firmado por la PSF) con las dependencias en Lib\site-packages, el código
    de la app en app\<versión>\ y el lanzador launch.pyw (ver docs/architecture.md §3 y §8).

    Pasos:
      1. (salvo -SkipTests) ruff + pytest con el Python del venv.
      2. Baja el Python embeddable fijado en packaging/runtime.json (caché en build/cache) y verifica su SHA-256.
      3. Arma build/stage: runtime\ (+ python313._pth y kd-runtime.json), dependencias de
         packaging/requirements-runtime.txt (con hashes), app\<versión>\kdownloader, current, launch.pyw,
         uninstall.cmd, K-Downloader.ico y licenses\.
      4. Verifica el runtime: importa las dependencias, revisa que no falte ninguna y corre --selftest.
      5. Genera en -OutDir: kd-bundle-<v>.zip, kd-app-<v>.zip, Instalar-K-Snag.cmd (y su copia
         Instalar-K-Downloader.cmd, el nombre anterior), Instalar-K-Snag.exe (el mismo instalador en C#,
         para firmarlo; necesita el SDK de .NET), latest.json y SHA256SUMS.txt.

.PARAMETER Version
    Versión a construir. Por defecto, __version__ de src/kdownloader/__init__.py (si se pasa, debe coincidir).

.PARAMETER BaseUrl
    URL donde se publicarán los archivos. Por defecto, la del Release v<Version> en GitHub.

.PARAMETER OutDir
    Carpeta de salida (relativa a la raíz del repo). Por defecto, dist.

.PARAMETER SkipTests
    No corre ruff ni pytest. Un --selftest fallido queda como aviso en vez de error.

.PARAMETER Python
    Intérprete para pip, ruff y pytest. Por defecto, .venv\Scripts\python.exe (o python del PATH).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File packaging\build.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$BaseUrl,
    [string]$OutDir = 'dist',
    [switch]$SkipTests,
    [string]$Python
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PackagingDir = $PSScriptRoot
$BuildDir = Join-Path $Repo 'build'
$CacheDir = Join-Path $BuildDir 'cache'
$Stage = Join-Path $BuildDir 'stage'
$WheelDir = Join-Path $BuildDir 'wheels'
$RepoName = 'Kasaco223/K-Snag'
$Clock = [Diagnostics.Stopwatch]::StartNew()

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

# --- Utilidades -------------------------------------------------------------------------------

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host ('==> ' + $Text) -ForegroundColor Cyan
}

function Write-Info([string]$Text) { Write-Host ('    ' + $Text) }

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    # Con 'Stop', PowerShell 5.1 convierte cualquier línea de stderr en error: se relaja solo aquí.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    if ($code -ne 0) { throw ('{0} falló (código {1})' -f $What, $code) }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Write-TextFile([string]$Path, [string]$Text, [switch]$Ascii) {
    if ($Ascii) { $enc = [Text.Encoding]::ASCII } else { $enc = New-Object Text.UTF8Encoding $false }
    [IO.File]::WriteAllText($Path, $Text, $enc)
}

function Remove-Tree([string]$Path) {
    # .NET no sigue uniones ni enlaces al borrar (Remove-Item de PowerShell 5.1 sí puede hacerlo).
    if (Test-Path -LiteralPath $Path) { [IO.Directory]::Delete($Path, $true) }
}

function Copy-Tree([string]$From, [string]$To) {
    [void](New-Item -ItemType Directory -Force -Path $To)
    foreach ($item in Get-ChildItem -LiteralPath $From -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
        if ($item.PSIsContainer) {
            if (@('__pycache__', '.pytest_cache', '.ruff_cache', '.mypy_cache') -contains $item.Name) { continue }
            Copy-Tree $item.FullName (Join-Path $To $item.Name)
        } elseif (@('.pyc', '.pyo') -notcontains $item.Extension.ToLowerInvariant()) {
            Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $To $item.Name)
        }
    }
}

function ConvertTo-JsonString([string]$Text) {
    foreach ($ch in $Text.ToCharArray()) {
        if ([int]$ch -lt 0x20) { throw ('Carácter de control en un valor JSON: ' + $Text) }
    }
    return '"' + $Text.Replace('\', '\\').Replace('"', '\"') + '"'
}

function ConvertTo-KdJson($Value, [int]$Indent = 0) {
    # ConvertTo-Json de PowerShell 5.1 indenta raro y escapa de más: este es simple y estable.
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [Collections.IDictionary]) {
        $pad = '  ' * ($Indent + 1)
        $parts = @()
        foreach ($key in $Value.Keys) {
            $parts += $pad + (ConvertTo-JsonString ([string]$key)) + ': ' + (ConvertTo-KdJson $Value[$key] ($Indent + 1))
        }
        return "{`n" + ($parts -join ",`n") + "`n" + ('  ' * $Indent) + '}'
    }
    if ($Value -is [bool]) { if ($Value) { return 'true' } else { return 'false' } }
    if ($Value -is [string]) { return ConvertTo-JsonString $Value }
    return [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0}', $Value)
}

function Get-ZipEntries([string]$Base, [string[]]$Items) {
    # Devuelve @{Path; Name} con nombres relativos a $Base y separados con '/', en orden estable.
    $baseFull = (Resolve-Path -LiteralPath $Base).Path.TrimEnd('\')
    $files = @()
    foreach ($item in $Items) {
        $full = Join-Path $baseFull $item
        if (Test-Path -LiteralPath $full -PathType Container) {
            $files += @(Get-ChildItem -LiteralPath $full -Recurse -File -Force)
        } else {
            $files += @(Get-Item -LiteralPath $full -Force)
        }
    }
    $entries = @()
    foreach ($f in ($files | Sort-Object FullName)) {
        $entries += @{ Path = $f.FullName; Name = $f.FullName.Substring($baseFull.Length + 1).Replace('\', '/') }
    }
    return , $entries
}

function New-KdZip([string]$ZipPath, [object[]]$Entries) {
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force }
    $minDate = New-Object DateTimeOffset 1980, 1, 2, 0, 0, 0, ([TimeSpan]::Zero)
    $fs = [IO.File]::Open($ZipPath, [IO.FileMode]::CreateNew)
    $zip = New-Object IO.Compression.ZipArchive $fs, ([IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($e in $Entries) {
            $entry = $zip.CreateEntry($e.Name, [IO.Compression.CompressionLevel]::Optimal)
            $stamp = New-Object DateTimeOffset ([IO.File]::GetLastWriteTimeUtc($e.Path))
            if ($stamp -lt $minDate) { $stamp = $minDate }
            $entry.LastWriteTime = $stamp
            $src = [IO.File]::OpenRead($e.Path)
            $dst = $entry.Open()
            try { $src.CopyTo($dst) } finally { $dst.Dispose(); $src.Dispose() }
        }
    } finally {
        $zip.Dispose()
        $fs.Dispose()
    }
}

function Format-Size([long]$Bytes) { return ('{0:N1} MB' -f ($Bytes / 1MB)) }

# --- Inicio ----------------------------------------------------------------------------------

Push-Location $Repo
try {
    # Versión
    $initText = [IO.File]::ReadAllText((Join-Path $Repo 'src\kdownloader\__init__.py'))
    if ($initText -notmatch '__version__\s*=\s*"([^"]+)"') { throw 'No se encontró __version__ en src/kdownloader/__init__.py' }
    $codeVersion = $Matches[1]
    if (-not $Version) { $Version = $codeVersion }
    if ($Version -ne $codeVersion) { throw ('-Version {0} no coincide con __version__ = {1}' -f $Version, $codeVersion) }
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw ('Versión inválida (se espera X.Y.Z): ' + $Version) }
    if ($env:GITHUB_REF_TYPE -eq 'tag' -and $env:GITHUB_REF_NAME -and $env:GITHUB_REF_NAME -ne ('v' + $Version)) {
        throw ('El tag {0} no coincide con la versión {1}' -f $env:GITHUB_REF_NAME, $Version)
    }
    if (-not $BaseUrl) { $BaseUrl = 'https://github.com/{0}/releases/download/v{1}' -f $RepoName, $Version }
    $BaseUrl = $BaseUrl.TrimEnd('/')
    if ([IO.Path]::IsPathRooted($OutDir)) { $Out = $OutDir } else { $Out = Join-Path $Repo $OutDir }

    if (-not $Python) {
        $venvPython = Join-Path $Repo '.venv\Scripts\python.exe'
        if (Test-Path -LiteralPath $venvPython) { $Python = $venvPython } else { $Python = (Get-Command python).Source }
    }
    Write-Step ('K-Snag {0} -> {1}' -f $Version, $Out)
    Write-Info ('Python del build: ' + $Python)
    Write-Info ('URL base: ' + $BaseUrl)

    # 1. Pruebas
    if (-not $SkipTests) {
        Write-Step 'ruff + pytest'
        Invoke-Native $Python @('-m', 'ruff', 'check', 'src', 'tests', 'packaging') 'ruff'
        Invoke-Native $Python @('-m', 'pytest', '-q') 'pytest'
    }

    # 2. Python embeddable
    $rt = [IO.File]::ReadAllText((Join-Path $PackagingDir 'runtime.json')) | ConvertFrom-Json
    $pyParts = $rt.version.Split('.')
    $pyShort = $pyParts[0] + '.' + $pyParts[1]
    $embedName = Split-Path -Leaf ([Uri]$rt.url).AbsolutePath
    $embedZip = Join-Path $CacheDir $embedName
    Write-Step ('Python embeddable ' + $rt.version + ' (' + $rt.runtime_id + ')')
    [void](New-Item -ItemType Directory -Force -Path $CacheDir)
    if ((Test-Path -LiteralPath $embedZip) -and (Get-Sha256 $embedZip) -eq $rt.sha256) {
        Write-Info ('En caché: ' + $embedZip)
    } else {
        $tmp = $embedZip + '.part'
        Write-Info ('Descargando ' + $rt.url)
        $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
        if (Test-Path -LiteralPath $curl) {
            Invoke-Native $curl @('-L', '--fail', '--silent', '--show-error', '--retry', '3', '-o', $tmp, $rt.url) 'La descarga de Python'
        } else {
            Invoke-WebRequest -Uri $rt.url -OutFile $tmp -UseBasicParsing
        }
        $got = Get-Sha256 $tmp
        if ($got -ne $rt.sha256) {
            Remove-Item -LiteralPath $tmp -Force
            throw ('SHA-256 del Python embeddable no coincide: esperado {0}, obtenido {1}' -f $rt.sha256, $got)
        }
        Move-Item -LiteralPath $tmp -Destination $embedZip -Force
    }
    Write-Info ('SHA-256 verificado: ' + $rt.sha256)

    # 3. Stage
    Write-Step 'Armando build/stage'
    Remove-Tree $Stage
    $RuntimeDir = Join-Path $Stage 'runtime'
    [void](New-Item -ItemType Directory -Force -Path $RuntimeDir)
    [IO.Compression.ZipFile]::ExtractToDirectory($embedZip, $RuntimeDir)
    $pth = @(Get-ChildItem -LiteralPath $RuntimeDir -Filter 'python3*._pth')
    $stdlib = @(Get-ChildItem -LiteralPath $RuntimeDir -Filter 'python3*.zip')
    if ($pth.Count -ne 1 -or $stdlib.Count -ne 1) { throw 'El Python embeddable no tiene el ._pth o el .zip esperados' }
    Write-TextFile $pth[0].FullName ("{0}`r`n.`r`nLib\site-packages`r`nimport site`r`n" -f $stdlib[0].Name) -Ascii
    Write-Info ($pth[0].Name + ': ' + $stdlib[0].Name + ', ., Lib\site-packages, import site')

    $SitePackages = Join-Path $RuntimeDir 'Lib\site-packages'
    [void](New-Item -ItemType Directory -Force -Path $SitePackages)
    $reqFile = Join-Path $PackagingDir 'requirements-runtime.txt'
    $wheelLines = @()
    $sdistLines = @()
    foreach ($line in [IO.File]::ReadAllLines($reqFile)) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith('#')) { continue }
        if ($t -match '#\s*sdist\b') { $sdistLines += ($t -replace '\s*#.*$', '') } else { $wheelLines += ($t -replace '\s*#.*$', '') }
    }
    $wheelReq = Join-Path $BuildDir 'req-wheels.txt'
    $sdistReq = Join-Path $BuildDir 'req-sdist.txt'
    Write-TextFile $wheelReq (($wheelLines -join "`n") + "`n")
    Remove-Tree $WheelDir
    [void](New-Item -ItemType Directory -Force -Path $WheelDir)
    $pipBase = @('-m', 'pip', '--disable-pip-version-check', '--no-input')
    if ($sdistLines.Count -gt 0) {
        Write-Info ('Wheels desde sdist: ' + (($sdistLines | ForEach-Object { ($_ -split '\s')[0] }) -join ', '))
        Write-TextFile $sdistReq (($sdistLines -join "`n") + "`n")
        Invoke-Native $Python ($pipBase + @('wheel', '--no-deps', '--require-hashes', '--no-binary', ':all:', '-r', $sdistReq, '-w', $WheelDir)) 'pip wheel'
    }
    $pipTarget = $pipBase + @('install', '--no-compile', '--no-deps', '--target', $SitePackages, '--platform', 'win_amd64',
        '--python-version', $pyShort, '--implementation', 'cp', '--only-binary=:all:')
    Write-Info ('pip install: {0} paquetes (cp{1} win_amd64, con hashes)' -f ($wheelLines.Count + $sdistLines.Count), $pyShort.Replace('.', ''))
    Invoke-Native $Python ($pipTarget + @('--require-hashes', '-r', $wheelReq)) 'pip install'
    $built = @(Get-ChildItem -LiteralPath $WheelDir -Filter '*.whl')
    if ($built.Count -gt 0) {
        Invoke-Native $Python ($pipTarget + @($built | ForEach-Object { $_.FullName })) 'pip install (sdists)'
    }

    # Limpieza: nada de .exe nuevos (lanzadores de consola de pip), pruebas ni cachés.
    $stripped = @()
    foreach ($rel in @('bin', 'share', 'comtypes\test')) {
        $p = Join-Path $SitePackages $rel
        if (Test-Path -LiteralPath $p) { Remove-Tree $p; $stripped += $rel }
    }
    if (Test-Path -LiteralPath (Join-Path $SitePackages 'scripts\register_hkey_aumid.py')) {
        Remove-Tree (Join-Path $SitePackages 'scripts')
        $stripped += 'scripts (windows_toasts)'
    }
    foreach ($d in @(Get-ChildItem -LiteralPath $SitePackages -Recurse -Directory -Force -Filter '__pycache__')) {
        if (Test-Path -LiteralPath $d.FullName) { Remove-Tree $d.FullName }
    }
    foreach ($exe in @(Get-ChildItem -LiteralPath $SitePackages -Recurse -File -Force -Filter '*.exe')) {
        Remove-Item -LiteralPath $exe.FullName -Force
        $stripped += $exe.FullName.Substring($SitePackages.Length + 1)
    }
    if ($stripped.Count -gt 0) { Write-Info ('Quitado: ' + ($stripped -join ', ')) }

    $reqHash = Get-Sha256 $reqFile
    $runtimeInfo = [ordered]@{ runtime_id = $rt.runtime_id; python = $rt.version; requirements_sha256 = $reqHash }
    Write-TextFile (Join-Path $RuntimeDir 'kd-runtime.json') ((ConvertTo-KdJson $runtimeInfo) + "`n")

    # Código de la app y archivos de la raíz
    $AppDir = Join-Path $Stage ('app\' + $Version)
    Copy-Tree (Join-Path $Repo 'src\kdownloader') (Join-Path $AppDir 'kdownloader')
    Write-TextFile (Join-Path $Stage 'current') $Version -Ascii
    Copy-Item -LiteralPath (Join-Path $PackagingDir 'launch.pyw') -Destination $Stage
    Copy-Item -LiteralPath (Join-Path $PackagingDir 'uninstall.cmd') -Destination $Stage
    $icon = Join-Path $PackagingDir 'assets\K-Downloader.ico'
    if (-not (Test-Path -LiteralPath $icon)) { throw 'Falta packaging/assets/K-Downloader.ico (genéralo con packaging/make_icon.py)' }
    Copy-Item -LiteralPath $icon -Destination $Stage

    # Licencias
    $Lic = Join-Path $Stage 'licenses'
    [void](New-Item -ItemType Directory -Force -Path $Lic)
    Copy-Item -LiteralPath (Join-Path $RuntimeDir 'LICENSE.txt') -Destination (Join-Path $Lic 'Python-LICENSE.txt')
    Copy-Item -LiteralPath (Join-Path $Repo 'LICENSE') -Destination (Join-Path $Lic 'K-Snag-LICENSE.txt')
    $notices = Join-Path $Repo 'THIRD_PARTY_NOTICES.md'
    if (Test-Path -LiteralPath $notices) { Copy-Item -LiteralPath $notices -Destination $Lic }
    $licCount = 0
    foreach ($di in @(Get-ChildItem -LiteralPath $SitePackages -Directory -Filter '*.dist-info')) {
        $dest = Join-Path $Lic ($di.Name -replace '\.dist-info$', '')
        $files = @()
        $licDir = Join-Path $di.FullName 'licenses'
        if (Test-Path -LiteralPath $licDir) { $files += @(Get-ChildItem -LiteralPath $licDir -Recurse -File) }
        $files += @(Get-ChildItem -LiteralPath $di.FullName -File | Where-Object { $_.Name -match '^(LICEN[CS]E|COPYING|NOTICE|AUTHORS)' })
        [void](New-Item -ItemType Directory -Force -Path $dest)
        if ($files.Count -gt 0) {
            foreach ($f in $files) {
                $target = Join-Path $dest $f.Name
                if (Test-Path -LiteralPath $target) { $target = Join-Path $dest ($f.Directory.Name + '-' + $f.Name) }
                Copy-Item -LiteralPath $f.FullName -Destination $target
                $licCount++
            }
        } else {
            # Sin archivo de licencia en el wheel: se deja lo que declara su METADATA.
            $meta = [IO.File]::ReadAllLines((Join-Path $di.FullName 'METADATA')) |
                Where-Object { $_ -match '^(Name|Version|License|License-Expression|Classifier: License ::|Home-page|Project-URL|Author|Author-email):' }
            Write-TextFile (Join-Path $dest 'LICENSE-INFO.txt') (($meta -join "`r`n") + "`r`n")
            $licCount++
        }
    }
    $licReadme = @(
        'K-Snag se distribuye bajo la licencia MIT (K-Snag-LICENSE.txt).',
        '',
        'Este paquete incluye Python (PSF License, Python-LICENSE.txt) y las bibliotecas de',
        'runtime\Lib\site-packages; cada carpeta de licenses\ contiene la licencia de una de ellas.',
        'mutagen se distribuye bajo GPL-2.0-or-later y su código fuente completo está en',
        'runtime\Lib\site-packages\mutagen.',
        '',
        'K-Snag no incluye yt-dlp, FFmpeg ni Deno: se descargan aparte, desde sus sitios',
        'oficiales y verificados con SHA-256, la primera vez que abres la app.',
        '',
        'K-Snag is MIT-licensed. This bundle includes Python (PSF License) and the libraries in',
        'runtime\Lib\site-packages, each with its license under licenses\. mutagen is GPL-2.0-or-later and',
        'its complete source code is included. yt-dlp, FFmpeg and Deno are not redistributed.'
    )
    Write-TextFile (Join-Path $Lic 'README.txt') (($licReadme -join "`r`n") + "`r`n")
    Write-Info ('Licencias: {0} archivos' -f ($licCount + 3))

    # 4. Verificación del runtime
    Write-Step 'Verificando el runtime'
    $stagePython = Join-Path $RuntimeDir 'python.exe'
    $imports = 'import sys, webview, clr_loader, clr, comtypes, comtypes.shelllink, curl_cffi, brotli, websockets, mutagen, ' +
        'certifi, requests, urllib3, charset_normalizer, idna, bottle, proxy_tools, typing_extensions, cffi, ' +
        'windows_toasts; print(sys.version.split()[0], sys.prefix)'
    Invoke-Native $stagePython @('-B', '-I', '-c', $imports) 'Importar las dependencias con el runtime'
    Invoke-Native $Python @((Join-Path $PackagingDir 'check_deps.py'), $SitePackages) 'check_deps'
    # Smart App Control: ningún módulo nativo del runtime puede estar bloqueado por Windows.
    Invoke-Native $stagePython @('-B', '-I', (Join-Path $PackagingDir 'check_native.py'), $RuntimeDir) 'check_native (Smart App Control)'
    $oldData = $env:KD_DATA_DIR
    $env:KD_DATA_DIR = Join-Path $BuildDir 'selftest-data'
    $env:PYTHONDONTWRITEBYTECODE = '1'
    try {
        Invoke-Native $stagePython @('-B', (Join-Path $Stage 'launch.pyw'), '--selftest') 'launch.pyw --selftest'
    } catch {
        if ($SkipTests) { Write-Warning ('--selftest falló (se continúa por -SkipTests): ' + $_.Exception.Message) } else { throw }
    } finally {
        $env:KD_DATA_DIR = $oldData
        Remove-Item Env:\PYTHONDONTWRITEBYTECODE -ErrorAction SilentlyContinue
    }
    foreach ($d in @(Get-ChildItem -LiteralPath $Stage -Recurse -Directory -Force -Filter '__pycache__')) {
        if (Test-Path -LiteralPath $d.FullName) { Remove-Tree $d.FullName }
    }

    # 5. Artefactos
    Write-Step 'Generando artefactos'
    [void](New-Item -ItemType Directory -Force -Path $Out)
    $bundleName = 'kd-bundle-{0}.zip' -f $Version
    $appName = 'kd-app-{0}.zip' -f $Version
    $installerName = 'Instalar-K-Snag.cmd'
    $exeInstallerName = 'Instalar-K-Snag.exe'
    # Copia con el nombre de antes del cambio de nombre (K-Downloader): los enlaces viejos a
    # releases/latest/download/Instalar-K-Downloader.cmd siguen funcionando.
    $legacyInstallerName = 'Instalar-K-Downloader.cmd'
    $bundlePath = Join-Path $Out $bundleName
    $appPath = Join-Path $Out $appName
    $installerPath = Join-Path $Out $installerName

    $bundleEntries = Get-ZipEntries $Stage @(Get-ChildItem -LiteralPath $Stage -Force | ForEach-Object { $_.Name })
    New-KdZip $bundlePath $bundleEntries
    $appEntries = Get-ZipEntries $Stage @(('app\' + $Version), 'current')
    New-KdZip $appPath $appEntries
    $bundleSha = Get-Sha256 $bundlePath
    $appSha = Get-Sha256 $appPath
    $bundleSize = (Get-Item -LiteralPath $bundlePath).Length
    Write-Info ('{0}: {1} archivos, {2}' -f $bundleName, $bundleEntries.Count, (Format-Size $bundleSize))
    Write-Info ('{0}: {1} archivos, {2}' -f $appName, $appEntries.Count, (Format-Size (Get-Item -LiteralPath $appPath).Length))

    $template = [IO.File]::ReadAllText((Join-Path $PackagingDir 'installer\Instalar-K-Snag.cmd.template'))
    $bundleUrl = '{0}/{1}' -f $BaseUrl, $bundleName
    $rendered = $template.Replace('{{VERSION}}', $Version).Replace('{{BUNDLE_URL}}', $bundleUrl).
        Replace('{{BUNDLE_SHA256}}', $bundleSha).Replace('{{BUNDLE_NAME}}', $bundleName).
        Replace('{{BUNDLE_SIZE}}', [string]$bundleSize)
    if ($rendered.Contains('{{')) { throw 'Quedó un marcador {{...}} sin reemplazar en el instalador' }
    $rendered = ($rendered -replace "`r`n", "`n") -replace "`n", "`r`n"
    foreach ($ch in $rendered.ToCharArray()) {
        if ([int]$ch -gt 0x7E -or ([int]$ch -lt 0x20 -and $ch -ne "`r" -and $ch -ne "`n" -and $ch -ne "`t")) {
            throw ('El instalador debe ser ASCII imprimible; carácter U+{0:X4}' -f [int]$ch)
        }
    }
    Write-TextFile $installerPath $rendered -Ascii
    Copy-Item -LiteralPath $installerPath -Destination (Join-Path $Out $legacyInstallerName) -Force
    $installerSha = Get-Sha256 $installerPath

    # Instalar-K-Snag.exe: el mismo instalador en C# (.NET Framework 4.8), con los mismos datos del bundle.
    # Es el que se firmará con SignPath; mientras tanto se publica junto al .cmd.
    $dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
    if (-not $dotnet) { throw 'Falta el SDK de .NET (dotnet) para compilar Instalar-K-Snag.exe: https://dot.net' }
    $exeBuild = Join-Path $BuildDir 'installer-exe'
    Remove-Tree $exeBuild
    Invoke-Native $dotnet.Source @(
        'publish', (Join-Path $PackagingDir 'installer\exe\Instalar-K-Snag.csproj'), '-c', 'Release', '-o', $exeBuild, '-nologo',
        ('-p:KdVersion=' + $Version), ('-p:KdBundleUrl=' + $bundleUrl), ('-p:KdBundleSha256=' + $bundleSha),
        ('-p:KdBundleName=' + $bundleName), ('-p:KdBundleSize=' + $bundleSize)) 'dotnet publish'
    Copy-Item -LiteralPath (Join-Path $exeBuild $exeInstallerName) -Destination (Join-Path $Out $exeInstallerName) -Force

    $latest = [ordered]@{
        version   = $Version
        runtime   = $rt.runtime_id
        app       = [ordered]@{ url = '{0}/{1}' -f $BaseUrl, $appName; sha256 = $appSha }
        bundle    = [ordered]@{ url = $bundleUrl; sha256 = $bundleSha }
        installer = [ordered]@{ url = '{0}/{1}' -f $BaseUrl, $installerName; sha256 = $installerSha }
        notes_url = 'https://github.com/{0}/releases/tag/v{1}' -f $RepoName, $Version
    }
    $latestPath = Join-Path $Out 'latest.json'
    Write-TextFile $latestPath ((ConvertTo-KdJson $latest) + "`n")

    $sumsPath = Join-Path $Out 'SHA256SUMS.txt'
    $sums = foreach ($name in @($installerName, $legacyInstallerName, $exeInstallerName, $appName, $bundleName, 'latest.json')) {
        '{0}  {1}' -f (Get-Sha256 (Join-Path $Out $name)), $name
    }
    Write-TextFile $sumsPath (($sums -join "`n") + "`n")

    # Resumen
    $stageFiles = @(Get-ChildItem -LiteralPath $Stage -Recurse -File -Force)
    $stageBytes = ($stageFiles | Measure-Object -Property Length -Sum).Sum
    Write-Step ('Listo en {0:N0} s' -f $Clock.Elapsed.TotalSeconds)
    Write-Info ('Instalado ocupa {0} en {1} archivos (runtime {2})' -f (Format-Size $stageBytes), $stageFiles.Count,
        (Format-Size ((Get-ChildItem -LiteralPath $RuntimeDir -Recurse -File -Force | Measure-Object -Property Length -Sum).Sum)))
    foreach ($name in @($installerName, $exeInstallerName, $bundleName, $appName, 'latest.json', 'SHA256SUMS.txt')) {
        $item = Get-Item -LiteralPath (Join-Path $Out $name)
        Write-Info ('{0,-28} {1,12:N0} bytes  {2,9}  {3}' -f $name, $item.Length, (Format-Size $item.Length), (Get-Sha256 $item.FullName))
    }
} finally {
    Pop-Location
}
