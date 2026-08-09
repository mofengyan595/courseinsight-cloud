import asyncio
import os

from fastapi import FastAPI
from pydantic import BaseModel, Field


def configured_delay_ms() -> int:
    raw = os.getenv("AI_STUB_DELAY_MS", "100")
    try:
        delay = int(raw)
    except ValueError as exc:
        raise RuntimeError("AI_STUB_DELAY_MS must be an integer") from exc
    if delay < 0 or delay > 60_000:
        raise RuntimeError("AI_STUB_DELAY_MS must be between 0 and 60000")
    return delay


DELAY_MS = configured_delay_ms()
app = FastAPI(title="CourseInsight Performance AI Stub", version="1.0.0")


class AnalysisRequest(BaseModel):
    taskId: int = Field(gt=0)
    commentId: int = Field(gt=0)
    text: str
    language: str | None = None
    includeAdvice: bool = True


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "UP",
        "service": "courseinsight-performance-ai-stub",
        "delayMs": DELAY_MS,
        "deterministic": True,
        "bertEnabled": False,
        "llmEnabled": False,
    }


@app.post("/api/v1/analyze")
async def analyze(request: AnalysisRequest) -> dict[str, object]:
    if DELAY_MS:
        await asyncio.sleep(DELAY_MS / 1000)
    advice = None
    if request.includeAdvice:
        advice = {
            "summary": "Performance stub response",
            "problems": [{
                "aspect": "performance",
                "description": "Deterministic performance test response",
                "evidence": "stub",
            }],
            "suggestions": [{
                "aspect": "performance",
                "suggestion": "Use measured backend metrics.",
                "evidence": "stub",
                "actionType": "observe",
            }],
            "riskLevel": "low",
            "source": "performance_stub",
            "language": request.language or "zh",
            "fallbackReason": None,
        }
    return {
        "taskId": request.taskId,
        "commentId": request.commentId,
        "language": request.language or "zh",
        "sentiment": "neutral",
        "confidence": 0.9,
        "sentimentSource": "performance_stub",
        "sentimentDevice": "stub",
        "topics": ["performance"],
        "topicEvidence": [{
            "aspect": "performance",
            "keywords": ["deterministic"],
            "evidence": "stub",
        }],
        "keywords": ["performance", "stub"],
        "longTextHandled": False,
        "longTextTruncated": False,
        "advice": advice,
    }
