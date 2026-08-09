[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidateRange(1, 100)]
    [int]$CourseCount = 50,
    [ValidatePattern('^[A-Za-z0-9_-]{3,20}$')]
    [string]$DatasetPrefix = 'PERF_P1',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$username = $env:PERF_USERNAME
$password = $env:PERF_PASSWORD
if ([string]::IsNullOrWhiteSpace($username) -or
    [string]::IsNullOrWhiteSpace($password)) {
    throw 'PERF_USERNAME and PERF_PASSWORD must be set in the current process.'
}
if ($username -notmatch '^[A-Za-z0-9_]{3,32}$') {
    throw 'PERF_USERNAME must match [A-Za-z0-9_]{3,32}.'
}
if ($password.Length -lt 8 -or $password.Length -gt 64) {
    throw 'PERF_PASSWORD must contain 8 to 64 characters.'
}

$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $performanceRoot 'results\runtime-context.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LiteralPath,
        [Parameter(Mandatory = $true)]
        [string]$Content
    )
    $normalized = $Content.Replace("`r`n", "`n")
    [IO.File]::WriteAllText(
        $LiteralPath,
        $normalized + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-CourseInsightJson {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [object]$Body,
        [string]$Token
    )

    $parameters = @{
        Method      = $Method
        Uri         = $Uri
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Depth 5
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $parameters.Headers = @{ Authorization = "Bearer $Token" }
    }
    Invoke-RestMethod @parameters
}

$registerBody = @{
    username    = $username
    password    = $password
    displayName = 'CourseInsight Performance User'
}
try {
    Invoke-CourseInsightJson `
        -Method Post `
        -Uri "$BaseUrl/api/auth/register" `
        -Body $registerBody | Out-Null
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    if ($statusCode -ne 409) {
        throw
    }
}

$promotionSql = "UPDATE app_user SET role='TEACHER', status=1 WHERE username='$username';"
$promotionSqlBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($promotionSql)
)
& docker exec `
    -e "PERF_SQL_B64=$promotionSqlBase64" `
    courseinsight-mysql `
    sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -u${MYSQL_USER} ${MYSQL_DATABASE}' |
    Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to promote the isolated performance user in local MySQL.'
}

$login = Invoke-CourseInsightJson `
    -Method Post `
    -Uri "$BaseUrl/api/auth/login" `
    -Body @{ username = $username; password = $password }
if ($login.code -ne 0 -or [string]::IsNullOrWhiteSpace($login.data.accessToken)) {
    throw 'Performance user login did not return a JWT.'
}
if ($login.data.role -ne 'TEACHER') {
    throw "Expected TEACHER performance user, got $($login.data.role)."
}
$token = $login.data.accessToken

$encodedPrefix = [Uri]::EscapeDataString($DatasetPrefix)
$coursesResponse = Invoke-CourseInsightJson `
    -Method Get `
    -Uri "$BaseUrl/api/courses?page=1&size=100&keyword=$encodedPrefix" `
    -Token $token
$coursesByCode = @{}
foreach ($course in $coursesResponse.data.items) {
    $coursesByCode[$course.code] = $course
}

$courseIds = @()
for ($index = 1; $index -le $CourseCount; $index++) {
    $code = '{0}-{1:d3}' -f $DatasetPrefix, $index
    $payload = @{
        code        = $code
        name        = "Performance Baseline Course $index"
        teacherName = 'Performance Fixture'
        description = 'Synthetic, repeatable data for CourseInsight phase-one performance tests.'
    }

    if (-not $coursesByCode.ContainsKey($code)) {
        $created = Invoke-CourseInsightJson `
            -Method Post `
            -Uri "$BaseUrl/api/courses" `
            -Body $payload `
            -Token $token
        $courseId = [long]$created.data
    } else {
        $courseId = [long]$coursesByCode[$code].id
    }

    $updatePayload = @{
        code        = $payload.code
        name        = $payload.name
        teacherName = $payload.teacherName
        description = $payload.description
        status      = 1
    }
    Invoke-CourseInsightJson `
        -Method Put `
        -Uri "$BaseUrl/api/courses/$courseId" `
        -Body $updatePayload `
        -Token $token | Out-Null
    $courseIds += $courseId
}

$context = [ordered]@{
    schemaVersion   = 1
    preparedAt      = (Get-Date).ToUniversalTime().ToString('o')
    baseUrl         = $BaseUrl
    username        = $username
    userRole        = 'TEACHER'
    datasetPrefix   = $DatasetPrefix
    courseCount     = $courseIds.Count
    primaryCourseId = $courseIds[0]
    courseIds       = $courseIds
}
$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
Write-Utf8NoBom `
    -LiteralPath $OutputPath `
    -Content ($context | ConvertTo-Json -Depth 5)
Write-Host "Prepared $($courseIds.Count) isolated courses. Context: $OutputPath"
