param(
    [ValidateSet("all", "fast", "integration")]
    [string]$Suite = "all"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$serverPath = Join-Path $projectRoot "courseinsight-server"
$mavenWrapper = Join-Path $serverPath "mvnw.cmd"

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Missing courseinsight-server/mvnw.cmd."
}

$mavenArguments = @("test")
if ($Suite -eq "fast") {
    $mavenArguments += '-Dgroups=!integration'
}
elseif ($Suite -eq "integration") {
    $mavenArguments += '-Dgroups=integration'
}

Push-Location $serverPath
try {
    & $mavenWrapper @mavenArguments
    $mavenExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($mavenExitCode -ne 0) {
    throw "Maven tests failed with exit code $mavenExitCode."
}
