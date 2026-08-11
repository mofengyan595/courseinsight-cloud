#!/usr/bin/env bash
set -Eeuo pipefail

ACTION="${1:-start}"
OPTION="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEMO_ENV="${REPOSITORY_ROOT}/demo.env"
DEMO_COMPOSE="${REPOSITORY_ROOT}/compose.demo.yaml"
SMOKE_SOURCE="${SCRIPT_DIR}/DemoSmoke.java"
DEMO_AI_IMAGE="courseinsight-demo-ai-service:latest"
COMPOSE=(docker compose --env-file "${DEMO_ENV}" -f "${DEMO_COMPOSE}")
HEALTHY_CONTAINERS=(
  courseinsight-demo-mysql
  courseinsight-demo-redis
  courseinsight-demo-rocketmq-nameserver
  courseinsight-demo-rocketmq-broker
  courseinsight-demo-ai-stub
)

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$2"
}

require_docker() {
  require_command docker "Docker is required. Install Docker Desktop or Docker Engine."
  docker info >/dev/null 2>&1 || fail "Docker is installed but the Docker Engine is unavailable. Start Docker and retry."
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."
}

require_java() {
  require_command java "JDK 17 is required and java must be available on PATH."
  local version
  version="$(java -version 2>&1)"
  [[ "${version}" =~ version\ \"17([.\"]){1} ]] || fail "JDK 17 is required. Detected: ${version%%$'\n'*}"
}

assert_dependencies_healthy() {
  local container state runtime_status health_status
  for container in "${HEALTHY_CONTAINERS[@]}"; do
    state="$(docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container}" 2>/dev/null)" \
      || fail "Required demo container '${container}' is not running. Run scripts/demo.sh start first."
    runtime_status="${state%%|*}"
    health_status="${state#*|}"
    [[ "${runtime_status}" == "running" ]] \
      || fail "Demo container '${container}' is not running (status=${runtime_status}). Run scripts/demo.sh start first."
    [[ "${health_status}" == "healthy" ]] \
      || fail "Demo container '${container}' is not healthy (health=${health_status})."
  done
  printf '[PASS] MySQL, Redis, RocketMQ and deterministic AI Stub are healthy\n'
}

start_demo_dependencies() {
  if [[ "${OPTION}" != "--rebuild" ]] && docker image inspect "${DEMO_AI_IMAGE}" >/dev/null 2>&1; then
    printf '[INFO] Reusing local deterministic AI Stub image (offline-safe)\n'
    "${COMPOSE[@]}" up --detach --no-build --wait --wait-timeout 180
    return
  fi

  printf '[INFO] Building deterministic AI Stub; first build requires Docker Hub access\n'
  "${COMPOSE[@]}" up --detach --build --wait --wait-timeout 180 \
    || fail "AI Stub image is unavailable or rebuild failed. Check Docker access to auth.docker.io and registry-1.docker.io, then retry."
}

cd "${REPOSITORY_ROOT}"
[[ -f "${DEMO_ENV}" ]] || fail "Missing demo settings file: ${DEMO_ENV}"

case "${ACTION}" in
  start)
    require_docker
    require_java
    if [[ -z "$("${COMPOSE[@]}" ps -q 2>/dev/null)" ]]; then
      java "${SMOKE_SOURCE}" --check-demo-ports
    fi
    start_demo_dependencies
    assert_dependencies_healthy
    printf '[PASS] Demo dependencies are ready\n'
    printf 'Start the application in another terminal:\n'
    printf '  cd courseinsight-server\n'
    printf "  ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo\n"
    ;;
  ready)
    require_docker
    require_java
    assert_dependencies_healthy
    java "${SMOKE_SOURCE}" --health-only
    ;;
  smoke)
    require_docker
    require_java
    assert_dependencies_healthy
    java "${SMOKE_SOURCE}"
    ;;
  stop)
    require_docker
    "${COMPOSE[@]}" stop
    printf '[PASS] Demo containers stopped; demo data volumes were preserved\n'
    ;;
  clean)
    require_docker
    [[ "${OPTION}" == "--confirm-delete-data" ]] \
      || fail "clean deletes demo-only data volumes. Re-run with --confirm-delete-data."
    "${COMPOSE[@]}" down --volumes --remove-orphans
    printf '[PASS] Demo containers and demo-only data volumes removed\n'
    ;;
  *)
    fail "Usage: scripts/demo.sh {start [--rebuild]|ready|smoke|stop|clean [--confirm-delete-data]}"
    ;;
esac
