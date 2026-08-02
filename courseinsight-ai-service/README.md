# CourseInsight AI Service

该服务把原 `Wanny276/nlp_final` 中的 NLP 核心封装为独立 HTTP API，供 Java 后端调用。

镜像安装 CPU 版 PyTorch，并沿用原项目的自动降级逻辑：BERT 可用时优先使用 BERT，否则依次降级到 TF-IDF 和规则。

## 接口

- `GET /health`：查看服务状态和当前实际使用的情感分析后端。
- `POST /api/v1/analyze`：分析一条课程评价。
- `GET /docs`：FastAPI 自动生成的接口文档。

请求示例：

```json
{
  "taskId": 1,
  "commentId": 1,
  "text": "老师讲课很清楚，案例也很有帮助。",
  "includeAdvice": true
}
```

`includeAdvice` 默认为 `false`。设置为 `true` 时调用配置的大模型生成教学建议；未配置密钥或外部服务失败时，返回 `source=local_fallback` 的本地模板建议，不影响核心 NLP 分析结果。

## BERT 权重

将网盘中的 `model.safetensors` 放到：

```text
models/bert_model_final/model.safetensors
```

该文件体积较大，已同时被 Git 和 Docker 构建上下文忽略，不应提交或复制进镜像。Docker Compose 会在运行时把本地模型目录只读挂载到容器中。
