FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt requirements-dev.txt requirements-bert.txt ./
RUN pip install --no-cache-dir torch==2.13.0 --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir \
        -r requirements.txt \
        -r requirements-dev.txt \
        -r requirements-bert.txt

COPY app ./app
COPY src ./src
COPY data ./data
COPY models ./models
COPY tests ./tests
COPY pyproject.toml ./

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
