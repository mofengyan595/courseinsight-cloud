[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$ContextPath = ''
)

$ErrorActionPreference = 'Stop'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root

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
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $performanceRoot 'results\runtime-context.json'
}
if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "Performance context does not exist: $ContextPath"
}
$context = Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
$prefix = [string]$context.datasetPrefix
$username = [string]$context.username
if ($prefix -notmatch '^[A-Za-z0-9_-]{3,20}$' -or
    $username -notmatch '^[A-Za-z0-9_]{3,32}$') {
    throw 'The context contains an unsafe prefix or username.'
}

$processingSql = @"
SELECT COUNT(*)
FROM analysis_task task
INNER JOIN course ON course.id = task.course_id
WHERE course.code LIKE '${prefix}-%'
  AND task.status IN ('WAITING', 'PROCESSING');
"@
$processingSqlBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($processingSql)
)
$processing = & docker exec `
    -e "PERF_SQL_B64=$processingSqlBase64" `
    courseinsight-mysql `
    sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect performance tasks before cleanup.'
}
if ([int]$processing -ne 0) {
    throw "Cleanup refused: $processing performance tasks are still active."
}

$target = "dataset prefix '$prefix' and synthetic user '$username'"
if (-not $PSCmdlet.ShouldProcess($target, 'Delete isolated performance rows and cache keys')) {
    return
}

$cleanupSql = @"
DELETE result_row FROM analysis_result result_row
INNER JOIN course ON course.id = result_row.course_id
WHERE course.code LIKE '${prefix}-%';
DELETE outbox FROM analysis_outbox_event outbox
INNER JOIN analysis_task task ON task.id = outbox.task_id
INNER JOIN course ON course.id = task.course_id
WHERE course.code LIKE '${prefix}-%';
DELETE task FROM analysis_task task
INNER JOIN course ON course.id = task.course_id
WHERE course.code LIKE '${prefix}-%';
DELETE batch_row FROM analysis_batch batch_row
INNER JOIN course ON course.id = batch_row.course_id
WHERE course.code LIKE '${prefix}-%';
DELETE comment_row FROM course_comment comment_row
INNER JOIN course ON course.id = comment_row.course_id
WHERE course.code LIKE '${prefix}-%';
DELETE FROM course WHERE code LIKE '${prefix}-%';
DELETE FROM app_user WHERE username = '$username';
"@
$cleanupSqlBase64 = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($cleanupSql)
)
& docker exec `
    -e "PERF_SQL_B64=$cleanupSqlBase64" `
    courseinsight-mysql `
    sh -c 'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -u${MYSQL_USER} ${MYSQL_DATABASE}' |
    Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL performance-data cleanup failed.'
}

$keys = @()
foreach ($id in $context.courseIds) {
    $keys += "course:detail:$id"
    $keys += "course:analytics:summary:$id"
}
$keys += 'course:ranking:popular'
$keys += 'course:ranking:popular:ready'
if ($keys.Count -gt 0) {
    & docker exec courseinsight-redis redis-cli DEL @keys | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL rows were deleted, but Redis cache cleanup failed.'
    }
}
$context | Add-Member `
    -Force `
    -NotePropertyName cleanedAt `
    -NotePropertyValue (Get-Date).ToUniversalTime().ToString('o')
$context | Add-Member -Force -NotePropertyName dataPresent -NotePropertyValue $false
Write-Utf8NoBom `
    -LiteralPath $ContextPath `
    -Content ($context | ConvertTo-Json -Depth 5)
Write-Host "Removed isolated performance data for $target."
