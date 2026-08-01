$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot ".env"
$serverPath = Join-Path $projectRoot "courseinsight-server"
$mavenWrapper = Join-Path $serverPath "mvnw.cmd"

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Missing .env in the project root. Create it from .env.example first."
}

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Missing courseinsight-server/mvnw.cmd."
}

$loadedCount = 0
$lineNumber = 0

foreach ($rawLine in Get-Content -LiteralPath $envPath) {
    $lineNumber++
    $line = $rawLine.Trim()

    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
        continue
    }

    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -lt 1) {
        throw "Invalid .env entry at line $lineNumber. Expected KEY=VALUE."
    }

    $name = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()

    if ($value.Length -ge 2) {
        $isDoubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
        $isSingleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
        if ($isDoubleQuoted -or $isSingleQuoted) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    [Environment]::SetEnvironmentVariable($name, $value, "Process")
    $loadedCount++
}

if ($loadedCount -eq 0) {
    throw "No environment variables were loaded from .env."
}

Push-Location $serverPath
try {
    & $mavenWrapper test
    $mavenExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($mavenExitCode -ne 0) {
    throw "Maven tests failed with exit code $mavenExitCode."
}
