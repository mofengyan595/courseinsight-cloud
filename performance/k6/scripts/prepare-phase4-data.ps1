[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]{2,20}$')]
    [string]$UserPrefix = 'perf_p4',
    [ValidatePattern('^[A-Za-z0-9_-]{3,20}$')]
    [string]$DatasetPrefix = 'PERF_P4',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$password = $env:PERF_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'PERF_PASSWORD must be set in the current process.'
}
if ($password.Length -lt 8 -or $password.Length -gt 64) {
    throw 'PERF_PASSWORD must contain 8 to 64 characters.'
}

$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $performanceRoot 'results\phase-4-runtime-context.json'
}

function Write-Utf8NoBom {
    param([string]$LiteralPath, [string]$Content)
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n").TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-Api {
    param([string]$Method, [string]$Uri, [object]$Body, [string]$Token)
    $arguments = @{ Method = $Method; Uri = $Uri; ContentType = 'application/json' }
    if ($null -ne $Body) {
        $arguments.Body = $Body | ConvertTo-Json -Depth 5
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $arguments.Headers = @{ Authorization = "Bearer $Token" }
    }
    Invoke-RestMethod @arguments
}

function Invoke-MySql {
    param([string]$Sql)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    & docker exec -e "PERF_SQL_B64=$encoded" courseinsight-mysql sh -c `
        'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -u${MYSQL_USER} ${MYSQL_DATABASE}' |
        Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to prepare Phase 4 users in local MySQL.'
    }
}

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne 'UP') {
    throw 'CourseInsight server is not healthy.'
}

$usernames = @(1..5 | ForEach-Object { '{0}_{1:d2}' -f $UserPrefix, $_ })
$seedUsername = "${UserPrefix}_seed"
Invoke-MySql -Sql "DELETE FROM app_user WHERE username='$seedUsername';"
try {
    Invoke-Api -Method Post -Uri "$BaseUrl/api/auth/register" -Body @{
        username = $seedUsername
        password = $password
        displayName = 'Phase 4 Credential Seed'
    } | Out-Null
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 409) {
        throw
    }
}

$cloneStatements = @()
for ($index = 0; $index -lt $usernames.Count; $index++) {
    $username = $usernames[$index]
    $displayIndex = $index + 1
    $cloneStatements += @"
INSERT INTO app_user (username, password_hash, display_name, role, status)
SELECT '$username', password_hash, 'Phase 4 Performance User $displayIndex', 'TEACHER', 1
FROM app_user WHERE username='$seedUsername'
ON DUPLICATE KEY UPDATE
  password_hash=VALUES(password_hash),
  display_name=VALUES(display_name),
  role='TEACHER', status=1;
"@
}
$cloneStatements += "DELETE FROM app_user WHERE username='$seedUsername';"
Invoke-MySql -Sql ($cloneStatements -join "`n")

$principals = @()
for ($index = 0; $index -lt $usernames.Count; $index++) {
    $username = $usernames[$index]
    $login = Invoke-Api -Method Post -Uri "$BaseUrl/api/auth/login" -Body @{
        username = $username
        password = $password
    }
    if ($login.code -ne 0 -or $login.data.role -ne 'TEACHER' -or
        [string]::IsNullOrWhiteSpace($login.data.accessToken)) {
        throw "Phase 4 principal $username could not authenticate as TEACHER."
    }
    $token = $login.data.accessToken
    $code = '{0}-{1:d2}' -f $DatasetPrefix, ($index + 1)
    $encodedCode = [Uri]::EscapeDataString($code)
    $courses = Invoke-Api -Method Get -Uri "$BaseUrl/api/courses?page=1&size=20&keyword=$encodedCode" -Token $token
    $existing = @($courses.data.items | Where-Object { $_.code -eq $code }) | Select-Object -First 1
    if ($null -eq $existing) {
        $created = Invoke-Api -Method Post -Uri "$BaseUrl/api/courses" -Token $token -Body @{
            code = $code
            name = "Phase 4 Async Chain Course $($index + 1)"
            teacherName = 'Performance Fixture'
            description = 'Isolated data for the Phase 4 AI stub and RocketMQ benchmark.'
        }
        $courseId = [long]$created.data
    } else {
        $courseId = [long]$existing.id
    }
    Invoke-Api -Method Put -Uri "$BaseUrl/api/courses/$courseId" -Token $token -Body @{
        code = $code
        name = "Phase 4 Async Chain Course $($index + 1)"
        teacherName = 'Performance Fixture'
        description = 'Isolated data for the Phase 4 AI stub and RocketMQ benchmark.'
        status = 1
    } | Out-Null
    $principals += [ordered]@{ username = $username; courseId = $courseId; courseCode = $code }
}

$context = [ordered]@{
    schemaVersion = 1
    preparedAt = (Get-Date).ToUniversalTime().ToString('o')
    baseUrl = $BaseUrl
    userPrefix = $UserPrefix
    datasetPrefix = $DatasetPrefix
    principalCount = $principals.Count
    batchCountPerRun = 5
    rowsPerBatch = 200
    taskCountPerRun = 1000
    principals = $principals
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
Write-Utf8NoBom -LiteralPath $OutputPath -Content ($context | ConvertTo-Json -Depth 8)
Write-Host "Prepared five isolated Phase 4 principals and courses. Context: $OutputPath"
