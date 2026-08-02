from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator


def to_camel(value: str) -> str:
    first, *rest = value.split("_")
    return first + "".join(word.capitalize() for word in rest)


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class AnalyzeRequest(ApiModel):
    task_id: int = Field(gt=0)
    comment_id: int = Field(gt=0)
    text: str = Field(min_length=1, max_length=5000)
    include_advice: bool = False

    @field_validator("text")
    @classmethod
    def text_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("text must not be blank")
        return normalized


class TopicEvidence(ApiModel):
    aspect: str
    keywords: list[str]
    evidence: str


class AdviceProblem(ApiModel):
    aspect: str
    description: str
    evidence: str


class AdviceSuggestion(ApiModel):
    aspect: str
    suggestion: str
    evidence: str
    action_type: str


class ReviewAdvice(ApiModel):
    summary: str
    problems: list[AdviceProblem]
    suggestions: list[AdviceSuggestion]
    risk_level: str
    source: str
    language: str | None = None
    fallback_reason: str | None = None


class AnalyzeResponse(ApiModel):
    task_id: int
    comment_id: int
    language: str
    sentiment: str
    confidence: float = Field(ge=0, le=1)
    sentiment_source: str
    sentiment_device: str
    topics: list[str]
    topic_evidence: list[TopicEvidence]
    keywords: list[str]
    long_text_handled: bool
    long_text_truncated: bool
    advice: ReviewAdvice | None = None


class HealthResponse(ApiModel):
    status: str
    service: str
    configured_backend: str
    active_backend: str
    bert_ready: bool
    tfidf_available: bool
    llm_configured: bool
