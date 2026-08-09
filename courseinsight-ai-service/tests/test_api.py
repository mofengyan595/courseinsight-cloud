import logging

from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


def test_health_reports_bert_backend(monkeypatch) -> None:
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "courseinsight-ai-service"
    assert body["tfidfAvailable"] is True
    assert body["bertReady"] is True
    assert body["activeBackend"] == "bert"
    assert body["llmConfigured"] is False


def test_analyze_returns_original_nlp_result() -> None:
    response = client.post(
        "/api/v1/analyze",
        json={
            "taskId": 101,
            "commentId": 202,
            "text": "老师讲课很清楚，案例也很有帮助。",
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["taskId"] == 101
    assert body["commentId"] == 202
    assert body["language"] == "zh"
    assert body["sentiment"] in {"positive", "neutral", "negative"}
    assert 0 <= body["confidence"] <= 1
    assert body["sentimentSource"] in {
        "bert",
        "bert+rule",
        "tfidf",
        "tfidf+rule",
        "rule",
    }
    assert "授课方式" in body["topics"]
    assert body["advice"] is None


def test_analyze_falls_back_to_local_advice_without_api_key(monkeypatch) -> None:
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    response = client.post(
        "/api/v1/analyze",
        json={
            "taskId": 102,
            "commentId": 203,
            "text": "老师讲课很清楚，案例也很有帮助。",
            "includeAdvice": True,
        },
    )

    assert response.status_code == 200
    advice = response.json()["advice"]
    assert advice["source"] == "local_fallback"
    assert advice["fallbackReason"] == "RuntimeError"
    assert advice["suggestions"]


def test_analyze_logs_observation_phase_durations(monkeypatch, caplog) -> None:
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        response = client.post(
            "/api/v1/analyze",
            json={
                "taskId": 103,
                "commentId": 204,
                "text": "The course examples are clear and useful.",
                "includeAdvice": True,
            },
        )

    assert response.status_code == 200
    timing_messages = [
        record.getMessage()
        for record in caplog.records
        if record.getMessage().startswith("courseinsight_ai_phase_duration")
    ]
    assert any("phase=sentiment task_id=103" in item for item in timing_messages)
    assert any("phase=advice task_id=103" in item for item in timing_messages)


def test_analyze_rejects_blank_text() -> None:
    response = client.post(
        "/api/v1/analyze",
        json={"taskId": 1, "commentId": 2, "text": "   "},
    )

    assert response.status_code == 422
