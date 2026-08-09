# CourseInsight 项目协作说明

## 适用范围

- 本文件适用于整个仓库；若子目录以后新增 `AGENTS.md` 或 `AGENTS.override.md`，以更靠近目标文件的规则为准。
- 开始任务前先检查当前文件、配置、测试和 `git status`，不要根据历史记录或目录名称推断现状。
- 只修改完成当前目标所必需的内容，保留用户已有且与任务无关的改动。

## 项目结构

- `courseinsight-server/`：Java 17、Spring Boot 3.5.16、Maven Wrapper 后端。
- `courseinsight-ai-service/`：Python 3.12、FastAPI、BERT/TF-IDF/规则降级分析服务。
- `compose.yaml`：MySQL、Redis、RocketMQ 和 AI 服务的本地依赖编排。
- `docs/development.md`：本地开发、测试和服务启停命令的主要说明。
- `scripts/test.ps1`：从仓库根目录运行 Java 分层测试的统一入口。

## 实现约定

- 保持现有 Controller、Service、Mapper 分层：Controller 负责 HTTP 输入输出，Service 负责业务、权限与事务，Mapper 负责持久化。
- API 使用 DTO 和现有统一响应/异常结构，不直接暴露持久化实体。
- 权限校验必须在服务层覆盖对象归属，不能只依赖 Controller 或前端传参。
- 数据库结构变更通过新的 Flyway migration 完成，不重写已经存在或可能已执行的迁移。
- 修改异步分析、Outbox、重试或恢复逻辑时，保留现有幂等、所有权和条件更新保护；不要用无条件状态覆盖绕过并发控制。
- 保持项目现有命名、格式和中文文档风格，不进行无关重构或全量格式化。

## 测试与验证

- 优先从仓库根目录运行最小有效测试：
  - 快速测试：`.\scripts\test.ps1 -Suite fast`
  - 集成测试：`.\scripts\test.ps1 -Suite integration`
  - 全部 Java 测试：`.\scripts\test.ps1 -Suite all`
- `integration` 和 `all` 包含 MySQL/Redis Testcontainers 测试，需要 Docker Engine 可用。
- 普通单元、MVC、安全和 HTTP 合约测试不应依赖本机 MySQL、Redis、RocketMQ、AI 服务或根目录 `.env`。
- 仅在验证真实数据库或 Redis 语义时使用相应 Testcontainers；普通 AI、RocketMQ 和外部 HTTP 场景使用 mock/fake。
- 修改 `courseinsight-ai-service/` 后，在该目录使用项目 Python 环境运行 `python -m pytest -q`。
- 没有实际运行的命令不得描述为“已通过”；无法验证时说明原因和未验证范围。

## 本地运行与数据安全

- 从 `courseinsight-server/` 启动本地后端：
  `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'`
- `local` profile 使用 Spring Boot Docker Compose 的 `start-only` 生命周期；Java 退出后依赖服务会继续运行。
- 需要停止共享服务时，从仓库根目录运行 `docker compose stop`。
- 未经明确授权，不运行 `docker compose down --volumes`，也不删除 MySQL、Redis 或 RocketMQ 持久化数据。

## 敏感信息与大文件

- 不读取、输出、提交或写入日志：`.env` 中的密码、API Key、JWT 密钥、访问令牌和连接凭据。
- `.env.example` 只能包含占位值和非敏感示例。
- 不提交 `courseinsight-ai-service/models/bert_model_final/model.safetensors` 或其他本地模型权重、缓存和构建产物。

## Git 与交付

- 除非用户明确要求，不创建分支，不执行 stage、commit、push、stash、reset、clean、merge 或 rebase。
- 修改完成后按需检查 `git status` 和 `git diff`，确保只有本任务相关文件发生变化。
- 交付时简洁说明关键改动、实际运行的验证及尚未验证的风险。
