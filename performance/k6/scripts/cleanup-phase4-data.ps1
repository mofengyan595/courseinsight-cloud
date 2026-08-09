[CmdletBinding()]
param([string]$ContextPath = '')

$ErrorActionPreference = 'Stop'
$k6Root = Split-Path -Parent $PSScriptRoot
$performanceRoot = Split-Path -Parent $k6Root
if ([string]::IsNullOrWhiteSpace($ContextPath)) {
    $ContextPath = Join-Path $performanceRoot 'results\phase-4-runtime-context.json'
}
if (-not (Test-Path -LiteralPath $ContextPath)) {
    throw "Phase 4 context does not exist: $ContextPath"
}
$context = Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
if ([string]$context.datasetPrefix -notmatch '^[A-Za-z0-9_-]{3,20}$' -or
    [string]$context.userPrefix -notmatch '^[A-Za-z][A-Za-z0-9_]{2,20}$') {
    throw 'Refusing cleanup because context prefixes are invalid.'
}
$courseIds = @($context.principals | ForEach-Object { [long]$_.courseId })
if ($courseIds.Count -ne 5 -or @($courseIds | Where-Object { $_ -le 0 }).Count -ne 0) {
    throw 'Refusing cleanup because exactly five valid Phase 4 course IDs are required.'
}
$ids = $courseIds -join ','
$userPrefix = [string]$context.userPrefix

function Write-Utf8NoBom {
    param([string]$LiteralPath, [string]$Content)
    [IO.File]::WriteAllText(
        $LiteralPath,
        $Content.Replace("`r`n", "`n").TrimEnd() + "`n",
        (New-Object Text.UTF8Encoding($false))
    )
}

function Invoke-MySqlLines {
    param([string]$Sql)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $lines = & docker exec -e "PERF_SQL_B64=$encoded" courseinsight-mysql sh -c `
        'echo ${PERF_SQL_B64}|base64 -d|MYSQL_PWD=${MYSQL_PASSWORD} mysql -N -B -u${MYSQL_USER} ${MYSQL_DATABASE}'
    if ($LASTEXITCODE -ne 0) { throw 'Phase 4 cleanup SQL failed.' }
    @($lines)
}

$active = [long](Invoke-MySqlLines -Sql @"
SELECT COUNT(*) FROM analysis_task
WHERE course_id IN ($ids)
  AND (status IN ('WAITING','PROCESSING')
       OR (status='FAILED' AND dead_lettered_at IS NULL));
"@ | Select-Object -First 1)
if ($active -ne 0) {
    throw "Refusing cleanup while $active Phase 4 tasks are not terminal."
}

$deleted = Invoke-MySqlLines -Sql @"
START TRANSACTION;
DELETE result_row FROM analysis_result result_row
INNER JOIN analysis_task task ON task.id=result_row.task_id
WHERE task.course_id IN ($ids);
DELETE outbox FROM analysis_outbox_event outbox
INNER JOIN analysis_task task ON task.id=outbox.task_id
WHERE task.course_id IN ($ids);
DELETE FROM analysis_task WHERE course_id IN ($ids);
DELETE FROM analysis_batch WHERE course_id IN ($ids);
DELETE FROM course_comment WHERE course_id IN ($ids);
DELETE FROM course WHERE id IN ($ids);
DELETE FROM app_user WHERE username LIKE '${userPrefix}\_%';
COMMIT;
SELECT CONCAT('remainingCourses=', COUNT(*)) FROM course WHERE id IN ($ids);
"@
Write-Host ($deleted -join "`n")
$context | Add-Member -NotePropertyName dataPresent -NotePropertyValue $false -Force
$context | Add-Member -NotePropertyName cleanedAt `
    -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
Write-Utf8NoBom -LiteralPath $ContextPath `
    -Content ($context | ConvertTo-Json -Depth 8)
Write-Host 'Phase 4 synthetic database rows were removed after terminal-state verification.'
