$ErrorActionPreference = 'Stop'
$scriptsRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$temporaryRoot = Join-Path $repositoryRoot 'courseinsight-server/target/audit-cleanup-tests'
New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
$contextPath = Join-Path $temporaryRoot 'context.json'

# 拦截所有 Docker 调用；测试只收集 SQL，不连接数据库或 Redis。
$global:auditCleanupCapturedSql = [Collections.Generic.List[string]]::new()
$global:auditCleanupActiveTasks = 0
function docker {
    $global:LASTEXITCODE = 0
    $encodedArgument = @($args | Where-Object { "$_" -like 'PERF_SQL_B64=*' })
    if ($encodedArgument.Count -gt 0) {
        $sql = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(
                "$($encodedArgument[0])".Substring('PERF_SQL_B64='.Length)))
        $global:auditCleanupCapturedSql.Add($sql)
        if ($sql -match 'SELECT COUNT\(\*\)' -and $sql -notmatch 'DELETE') {
            if ($sql -notmatch "status\s*=\s*'FAILED'.*dead_lettered_at IS NULL") {
                # 多行 SQL 的显式检查，确保尚可重试的 FAILED 不会被漏掉。
                if ($sql -notmatch '(?s)status\s*=\s*''FAILED''.*dead_lettered_at IS NULL') {
                    return 0
                }
            }
            return $global:auditCleanupActiveTasks
        }
    } elseif ($args -notcontains 'UNLINK') {
        throw 'Unexpected Docker invocation in offline cleanup test.'
    }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Write-Context {
    [IO.File]::WriteAllText($contextPath, (@{
                datasetPrefix = 'PERF_X'; username = 'audit_user'; userPrefix = 'PERF_X'
                courseIds = @(1); principals = @(1..5 | ForEach-Object { @{ courseId = $_ } })
            } | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
}

try {
    Write-Context
    $global:auditCleanupActiveTasks = 1
    $refused = $false
    try {
        & (Join-Path $scriptsRoot 'cleanup-data.ps1') -ContextPath $contextPath -Confirm:$false
    } catch {
        if ($_.Exception.Message -notmatch 'still active') { throw }
        $refused = $true
    }
    Assert-True $refused 'Retrying FAILED tasks must block cleanup.'
    Assert-True ($global:auditCleanupCapturedSql.Count -eq 1) 'Cleanup wrote SQL despite active tasks.'

    Write-Context
    $global:auditCleanupCapturedSql.Clear()
    $global:auditCleanupActiveTasks = 0
    & (Join-Path $scriptsRoot 'cleanup-data.ps1') -ContextPath $contextPath -Confirm:$false
    $cleanupSql = $global:auditCleanupCapturedSql[1]
    Assert-True ($cleanupSql.Contains("LIKE 'PERF!_X-%' ESCAPE '!'") -and
        -not $cleanupSql.Contains("LIKE 'PERF_X-%'")) 'Dataset prefix must be literal.'
    Assert-True ($cleanupSql.Contains('START TRANSACTION;') -and $cleanupSql.Contains('COMMIT;')) 'Database cleanup must be atomic.'

    Write-Context
    $global:auditCleanupCapturedSql.Clear()
    & (Join-Path $scriptsRoot 'cleanup-phase4-data.ps1') -ContextPath $contextPath
    Assert-True ($global:auditCleanupCapturedSql[1].Contains("LIKE 'PERF!_X!_%' ESCAPE '!'") ) 'Phase 4 user prefix must be literal.'
    Write-Host 'Offline cleanup regression checks passed (Docker calls intercepted).'
} finally {
    Remove-Item -LiteralPath $contextPath -ErrorAction SilentlyContinue
}
