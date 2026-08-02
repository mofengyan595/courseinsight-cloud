from __future__ import annotations

from fastapi import FastAPI

from app.schemas import AnalyzeRequest, AnalyzeResponse, HealthResponse
from src.llm_client import LLMConfig
from src.nlp_analyzer import analyze_review, sentiment_runtime_status


app = FastAPI(
    title="CourseInsight AI Service",
    version="0.1.0",
    description="Course review analysis API extracted from the original CourseInsight NLP project.",
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    runtime = sentiment_runtime_status()
    return HealthResponse(
        status="UP",
        service="courseinsight-ai-service",
        configured_backend=str(runtime["configured_backend"]),
        active_backend=str(runtime["active_backend"]),
        bert_ready=bool(runtime["bert"]["ready"]),
        tfidf_available=bool(runtime["tfidf_available"]),
        llm_configured=bool(LLMConfig.from_env().api_key),
    )


@app.post("/api/v1/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    result = analyze_review(
        request.text,
        reference_reviews=[],
        use_llm=request.include_advice,
    )
    return AnalyzeResponse(
        task_id=request.task_id,
        comment_id=request.comment_id,
        language=str(result["language"]),
        sentiment=str(result["sentiment"]),
        confidence=float(result["confidence"]),
        sentiment_source=str(result["sentiment_source"]),
        sentiment_device=str(result["sentiment_device"]),
        topics=[str(topic) for topic in result["topics"]],
        topic_evidence=result["topic_evidence"],
        keywords=[str(keyword) for keyword in result["keywords"]],
        long_text_handled=bool(result["long_text_handled"]),
        long_text_truncated=bool(result["long_text_truncated"]),
        advice=result.get("llm_advice"),
    )
