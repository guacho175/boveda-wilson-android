param(
    [switch]$History
)

$ErrorActionPreference = "Stop"

# Solo patrones de alta confianza. Nunca imprimimos la coincidencia: como máximo, el archivo y
# el commit que requieren revisión. También se inspeccionan ejemplos: un sufijo documental no
# convierte una credencial real en segura.
$patterns = @(
    'AIza[0-9A-Za-z_-]{35}',
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '"type"[[:space:]]*:[[:space:]]*"service_account"',
    'gh[pousr]_[0-9A-Za-z]{36,}',
    'github_pat_[0-9A-Za-z_]{40,}',
    'FIREBASE_APPCHECK_DEBUG_TOKEN[[:space:]]*=[[:space:]]*[^[:space:]<]+'
)

$excludedPathspecs = @(':(exclude)scripts/scan-secrets.ps1')
$forbiddenSecretFilePattern = '(?i)(^|/)(google-services\.json|[^/]*\.(jks|keystore|p12|pfx|pem|key))$'

function Find-SecretFiles([string]$Revision) {
    $arguments = @('grep', '-I', '-l', '-E')
    foreach ($pattern in $patterns) {
        $arguments += @('-e', $pattern)
    }
    $arguments += @($Revision, '--', '.', $excludedPathspecs)

    $result = & git @arguments 2>$null
    if ($LASTEXITCODE -gt 1) {
        throw "git grep falló al inspeccionar $Revision"
    }
    return @($result)
}

function Find-WorkingTreeSecretFiles {
    $paths = @(& git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files falló al inspeccionar el árbol de trabajo"
    }

    foreach ($relativePath in $paths) {
        if ($relativePath -eq 'scripts/scan-secrets.ps1') {
            continue
        }
        if ($relativePath -match $forbiddenSecretFilePattern) {
            $relativePath
            continue
        }

        $absolutePath = Join-Path $PWD $relativePath
        try {
            $bytes = [System.IO.File]::ReadAllBytes($absolutePath)
            if ($bytes -contains 0) {
                continue
            }
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
            foreach ($pattern in $patterns) {
                $dotNetPattern = $pattern.Replace('[[:space:]]', '\s')
                if ([regex]::IsMatch($content, $dotNetPattern)) {
                    $relativePath
                    break
                }
            }
        } catch {
            throw "No se pudo inspeccionar un archivo del árbol de trabajo: $relativePath"
        }
    }
}

function Find-SecretFileNamesInHistory {
    foreach ($object in (& git rev-list --objects --all)) {
        $separator = $object.IndexOf(' ')
        if ($separator -lt 0) {
            continue
        }
        $path = $object.Substring($separator + 1)
        if ($path -match $forbiddenSecretFilePattern) {
            $path
        }
    }
}

$findings = [System.Collections.Generic.HashSet[string]]::new()
foreach ($path in Find-WorkingTreeSecretFiles) {
    [void]$findings.Add("WORKTREE:$path")
}
foreach ($path in Find-SecretFiles 'HEAD') {
    [void]$findings.Add("HEAD:$path")
}

if ($History) {
    foreach ($path in Find-SecretFileNamesInHistory) {
        [void]$findings.Add("HISTORY-FILENAME:$path")
    }
    foreach ($commit in (& git rev-list --all)) {
        foreach ($path in Find-SecretFiles $commit) {
            [void]$findings.Add("$commit`:$path")
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Error ("Posibles secretos de alta confianza en {0} ubicación(es). " +
        "Revise los identificadores commit:ruta; el contenido se oculta deliberadamente." -f $findings.Count)
    $findings | Sort-Object | ForEach-Object { Write-Host $_ }
    exit 1
}

Write-Host "Escaneo de secretos: sin coincidencias de alta confianza."
$global:LASTEXITCODE = 0
