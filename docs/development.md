# CourseInsight 本地开发与测试

## Java 测试

测试不读取根目录 `.env`，也不会启动根目录的完整 Compose 栈。MySQL 和 Redis
集成测试使用无固定宿主机端口的 Testcontainers；需要 Docker Engine 可用。
RocketMQ 和 AI 服务不属于默认测试基础设施。

在 `courseinsight-server` 目录运行：

```powershell
# 不启动容器：单元测试、MVC/security 切片和 HTTP mock 合约测试
.\mvnw.cmd test '-Dgroups=!integration'

# 仅运行 MySQL/Redis Testcontainers 集成测试
.\mvnw.cmd test '-Dgroups=integration'

# 完整测试，不排除任何测试
.\mvnw.cmd test
```

也可以从仓库根目录使用 PowerShell 脚本：

```powershell
.\scripts\test.ps1 -Suite fast
.\scripts\test.ps1 -Suite integration
.\scripts\test.ps1 -Suite all
```

## 启动本地应用

先确认 Docker Desktop / Docker Engine 可用，并从 `.env.example` 创建好根目录
`.env`。然后从 `courseinsight-server` 目录启动：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local'
```

`local` profile 会读取根目录 `.env` 和 `../compose.yaml`。Spring Boot 仅在服务未运行时
执行 Compose 启动，并等待
Compose healthcheck/readiness 通过。MySQL 和 Redis 使用 Spring Boot 识别的服务连接；
RocketMQ NameServer 与 AI 服务继续使用 `application.yaml` 中的本机映射端口。

在 IntelliJ IDEA 中启动时，将运行配置的 Active profiles 设置为 `local`，效果相同。

## 认证接口限流

`POST /api/auth/login` 同时按请求来源和规范化后的用户名限流，默认分别为每分钟
20 次、每 5 分钟 10 次；`POST /api/auth/register` 默认按请求来源每 10 分钟
5 次。阈值和窗口可通过 `.env.example` 中的 `AUTH_*` 配置调整。Redis 不可用时与
已有业务限流保持一致，暂时放行请求并记录警告，避免认证入口因缓存故障整体不可用。

当前请求来源只使用 Servlet 容器提供的 `getRemoteAddr()`，不会信任客户端直接发送的
`X-Forwarded-For` 或 `X-Real-IP`。部署在反向代理之后时，需要先在容器/框架层配置明确
可信的代理和 forwarded-header 处理，否则所有请求可能表现为同一个代理地址。

Compose 生命周期为 `start-only`。停止 Java 不会停止或重建 MySQL、Redis、
RocketMQ 和 AI 服务，下一次启动 Java 会复用已经运行的服务。需要手动停止共享服务时，
在仓库根目录运行：

```powershell
docker compose stop
```

再次启动 Java 会重新启动这些已停止的服务。若明确需要删除容器和网络，可运行
`docker compose down`；不要附加 `--volumes`，除非确实要删除本地数据库、Redis 和
RocketMQ 持久化数据。
