#!/bin/sh
set -eu

: "${PERF_COURSE_IDS:?PERF_COURSE_IDS is required}"
: "${PERF_STARTED_AT:?PERF_STARTED_AT is required}"
: "${PERF_EXPECTED_TASKS:=1000}"
: "${PERF_SAMPLE_SECONDS:=0.5}"

while true; do
  row="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql --raw -N -B -u"${MYSQL_USER}" "${MYSQL_DATABASE}" -e "
SELECT CONCAT_WS(CHAR(9),
  CAST(ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000) AS UNSIGNED),
  COUNT(*),
  COALESCE(SUM(status='WAITING'),0),
  COALESCE(SUM(status='PROCESSING'),0),
  COALESCE(SUM(status='FAILED' AND dead_lettered_at IS NULL),0),
  COALESCE(SUM(status='SUCCESS'),0),
  COALESCE(SUM(status='FAILED' AND dead_lettered_at IS NOT NULL),0))
FROM analysis_task
WHERE course_id IN (${PERF_COURSE_IDS})
  AND created_at >= '${PERF_STARTED_AT}';")"
  outbox="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql --raw -N -B -u"${MYSQL_USER}" "${MYSQL_DATABASE}" -e "
SELECT CONCAT_WS(CHAR(9),
  COALESCE(SUM(outbox.status='PENDING'),0),
  COALESCE(SUM(outbox.status='PUBLISHING'),0),
  COALESCE(SUM(outbox.status='FAILED'),0))
FROM analysis_outbox_event outbox
INNER JOIN analysis_task task ON task.id=outbox.task_id
WHERE task.course_id IN (${PERF_COURSE_IDS})
  AND task.created_at >= '${PERF_STARTED_AT}';")"
  printf '%s\t%s\n' "${row}" "${outbox}"
  total="$(printf '%s' "${row}" | cut -f2)"
  waiting="$(printf '%s' "${row}" | cut -f3)"
  processing="$(printf '%s' "${row}" | cut -f4)"
  retrying="$(printf '%s' "${row}" | cut -f5)"
  pending="$(printf '%s' "${outbox}" | cut -f1)"
  publishing="$(printf '%s' "${outbox}" | cut -f2)"
  if [ "${total:-0}" -ge "${PERF_EXPECTED_TASKS}" ] &&
     [ "$((waiting + processing + retrying + pending + publishing))" -eq 0 ]; then
    break
  fi
  sleep "${PERF_SAMPLE_SECONDS}"
done
