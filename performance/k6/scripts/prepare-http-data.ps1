[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(100, 99999)]
    [int]$CourseCount = 60010,
    [ValidatePattern('^[A-Za-z0-9_-]{3,16}$')]
    [string]$DatasetPrefix = 'PERF_HTTP_P3',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$username = $env:PERF_USERNAME
$password = $env:PERF_PASSWORD
if ($username -notmatch '^[A-Za-z0-9_]{3,32}$') {
    throw 'PERF_USERNAME must match [A-Za-z0-9_]{3,32}.'
}
if ([string]::IsNullOrWhiteSpace($password) -or
    $password.Length -lt 8 -or $password.Length -gt 64) {
    throw 'PERF_PASSWORD must contain 8 to 64 characters.'
}

$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $performanceRoot 'results\phase-3-runtime-context.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$LiteralPath,
        [Parameter(Mandatory = $true)][string]$Content
    )
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n") + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-CourseInsightJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [object]$Body
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        ContentType = 'application/json'
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Depth 5
    }
    Invoke-RestMethod @parameters
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [switch]$Tabular
    )
    $sqlBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $arguments = @(
        'exec', '-e', "PERF_SQL_B64=$sqlBase64", 'courseinsight-mysql',
        'sh', '-c',
        'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    )
    $output = & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL command for HTTP benchmark data failed.'
    }
    if ($Tabular) {
        return @($output)
    }
}

try {
    Invoke-CourseInsightJson -Method Post -Uri "$BaseUrl/api/auth/register" -Body @{
        username = $username
        password = $password
        displayName = 'CourseInsight HTTP Benchmark User'
    } | Out-Null
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -ne 409) {
        throw
    }
}

Invoke-MySql -Sql @"
UPDATE app_user
SET role = 'TEACHER', status = 1
WHERE username = '$username';
"@

$login = Invoke-CourseInsightJson -Method Post -Uri "$BaseUrl/api/auth/login" -Body @{
    username = $username
    password = $password
}
if ($login.code -ne 0 -or [string]::IsNullOrWhiteSpace($login.data.accessToken) -or
    $login.data.role -ne 'TEACHER') {
    throw 'The isolated benchmark user could not obtain a TEACHER JWT.'
}

$insertSql = @"
INSERT INTO course (
    code, name, teacher_name, owner_user_id, description, status
)
SELECT CONCAT('$DatasetPrefix-', LPAD(numbers.n, 5, '0')),
       CONCAT('HTTP Benchmark Course ', numbers.n),
       'Performance Fixture',
       benchmark_user.id,
       'Synthetic isolated data for strict cache A/B and HTTP capacity tests.',
       1
FROM (
    SELECT ones.d + tens.d * 10 + hundreds.d * 100 + thousands.d * 1000
           + ten_thousands.d * 10000 + 1 AS n
    FROM
        (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN
        (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN
        (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN
        (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
    CROSS JOIN
        (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) numbers
CROSS JOIN app_user benchmark_user
WHERE benchmark_user.username = '$username'
  AND numbers.n <= $CourseCount
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    teacher_name = VALUES(teacher_name),
    owner_user_id = VALUES(owner_user_id),
    description = VALUES(description),
    status = 1;
"@
Invoke-MySql -Sql $insertSql

$idLines = Invoke-MySql -Tabular -Sql @"
SELECT id
FROM course
WHERE code LIKE '$DatasetPrefix-%'
ORDER BY code;
"@
$courseIds = @($idLines | ForEach-Object { [long]$_.Trim() })
if ($courseIds.Count -ne $CourseCount) {
    throw "Expected $CourseCount isolated courses, found $($courseIds.Count)."
}

$context = [ordered]@{
    schemaVersion = 3
    preparedAt = (Get-Date).ToUniversalTime().ToString('o')
    gitCommit = (& git rev-parse HEAD).Trim()
    baseUrl = $BaseUrl
    username = $username
    userRole = 'TEACHER'
    datasetPrefix = $DatasetPrefix
    courseCount = $courseIds.Count
    primaryCourseId = $courseIds[0]
    courseIds = $courseIds
    dataPresent = $true
}
$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
Write-Utf8NoBom -LiteralPath $OutputPath -Content ($context | ConvertTo-Json -Depth 5)
Write-Host "Prepared $($courseIds.Count) isolated HTTP benchmark courses."
Write-Host "Context: $OutputPath"
