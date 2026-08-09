# CourseInsight performance AI Stub

This standalone FastAPI service implements the existing
`POST /api/v1/analyze` contract for performance experiments. It returns a
deterministic response after a configurable non-blocking delay and never loads
BERT or calls an LLM.

Start it from the repository root:

```powershell
$env:AI_STUB_DELAY_MS = '100'
docker compose -f performance/ai-stub/compose.yaml up -d --build
```

Point the local Java process at `http://127.0.0.1:8002` with
`AI_SERVICE_BASE_URL`. Normal `compose.yaml` and the production AI service are
not changed. Stop the stub with:

```powershell
docker compose -f performance/ai-stub/compose.yaml stop
```
