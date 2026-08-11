# FinSight Enterprise Backend

FinSight Enterprise Backend 是一个基于 Spring Boot 3 的企业级智能管理平台后端。项目围绕企业后台常见能力建设，提供用户、角色、菜单、部门、日志、监控、文件中心、工作流、即时通讯、AI 模型对话与 RAG 知识库能力，适合作为企业内部管理系统、智能办公平台、知识库问答平台的后端基础工程。

> 当前仓库是后端工程。前端工程位于 `../../1web/financial_intelligence_system/`，前端技术栈为 Vue 3、TypeScript、Element Plus。

## 目录

- [项目特性](#项目特性)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [环境要求](#环境要求)
- [快速启动](#快速启动)
- [配置说明](#配置说明)
- [默认账号](#默认账号)
- [接口文档](#接口文档)
- [核心模块说明](#核心模块说明)
- [RAG 知识库说明](#rag-知识库说明)
- [数据库迁移](#数据库迁移)
- [常用接口](#常用接口)
- [开发规范](#开发规范)
- [安全建议](#安全建议)
- [常见问题](#常见问题)
- [开源说明](#开源说明)

## 项目特性

- 企业级 RBAC 权限体系：用户、角色、菜单、部门、按钮权限、二级/三级/多级菜单。
- JWT + Spring Security：无状态认证、接口权限校验、统一 401 响应。
- Redis 安全增强：登录失败限制、Token 黑名单、在线状态、普通用户单设备在线、管理员强制退出用户。
- 中文 Swagger/OpenAPI：接口分组清晰，支持 Bearer Token 调试。
- 登录日志、操作日志、模型对话日志：满足后台审计和运维追踪。
- 系统监控：服务器、JVM、磁盘、Redis、PostgreSQL 实时指标。
- 文件中心：本地存储和 MinIO/S3 存储可切换，支持上传、下载、在线预览、Office 转 PDF 预览。
- 工作流：基于 Flowable BPMN 2.0 的流程定义、部署、发起、审批、驳回、实例查询。
- 系统内即时通讯：用户单聊、群聊、未读消息、文件消息、撤回、搜索、WebSocket 实时推送。
- AI 模型管理：Chat 模型与 Embedding 模型分开配置，可在管理员后台维护和测试。
- 大模型对话：支持普通对话、SSE 流式输出、会话历史、Markdown 前端渲染。
- RAG 知识库：基于 PostgreSQL 16 + pgvector，支持文档解析、父子切片、向量检索、全文检索、RRF 融合召回、引用来源返回。

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 基础框架 | Java 21、Spring Boot 3.5.4 |
| Web | Spring MVC、Spring Validation |
| 安全 | Spring Security、JWT、BCrypt |
| 数据库 | PostgreSQL 16、pgvector |
| 数据访问 | Spring JDBC `JdbcClient` |
| 数据迁移 | Flyway |
| 缓存/会话 | Redis、Spring Data Redis |
| 接口文档 | SpringDoc OpenAPI、Swagger UI |
| 文件存储 | 本地文件系统、MinIO/S3 |
| 工作流 | Flowable 7.2 |
| 文档解析 | PDFBox、Apache POI |
| Office 预览 | LibreOffice/soffice 转 PDF |
| 实时通信 | Spring WebSocket |

## 系统架构

```text
前端 Vue 3
  |
  | HTTP / SSE / WebSocket
  v
Spring Boot Backend
  |
  |-- Security Filter Chain
  |     |-- JWT 校验
  |     |-- Redis Token 黑名单
  |     |-- 普通用户单设备在线校验
  |
  |-- Controller
  |     |-- AuthController
  |     |-- SystemController
  |     |-- AiController
  |     |-- KnowledgeController
  |     |-- FileController
  |     |-- WorkflowController
  |     |-- ImController
  |     |-- LogController
  |     |-- MonitorController
  |
  |-- Service
  |     |-- AI 调用与流式响应
  |     |-- RAG 文档解析、切片、向量化、混合检索
  |     |-- 文件存储、本地/MinIO 切换
  |     |-- Redis 会话安全
  |     |-- 审计日志
  |
  |-- PostgreSQL 16 + pgvector
  |-- Redis
  |-- MinIO/S3 可选
  |-- 大模型服务 OpenAI Compatible API
```

## 目录结构

```text
Financial_Intelligence_System/
├── pom.xml
├── mvnw
├── README.md
├── HELP.md
├── data/
│   └── uploads/                  # 默认本地文件上传目录
└── src/
    ├── main/
    │   ├── java/com/rpa/financial_intelligence_system/
    │   │   ├── FinancialIntelligenceSystemApplication.java
    │   │   ├── common/            # 统一响应、全局异常处理
    │   │   ├── config/            # 安全、OpenAPI、异步、WebSocket 配置
    │   │   ├── controller/        # REST API 控制器
    │   │   ├── security/          # JWT、用户认证、操作日志过滤器
    │   │   └── service/           # 业务服务、AI、RAG、文件、Redis、IM
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/      # Flyway 数据库迁移脚本
    └── test/
```

## 环境要求

- JDK 21+
- Maven 3.9+，也可以直接使用项目自带的 `./mvnw`
- PostgreSQL 16+
- PostgreSQL 扩展：pgvector
- Redis 6+
- 可选：MinIO 或兼容 S3 的对象存储
- 可选：LibreOffice，用于 Word、Excel、PPT 转 PDF 在线预览

## 快速启动

### 1. 创建数据库

请先确认 PostgreSQL 已安装 pgvector 扩展。macOS 可通过 Homebrew 安装：

```bash
brew install pgvector
```

创建数据库：

```bash
createdb -U postgres financial_ai
```

如果需要手动初始化扩展：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. 启动 Redis

本地默认连接：

```text
host: localhost
port: 6379
password: 空
```

### 3. 修改配置

默认配置位于 `src/main/resources/application.yaml`，也可以通过环境变量覆盖。

本地默认数据库配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/financial_ai
    username: postgres
    password: 123456
```

### 4. 启动后端

```bash
./mvnw spring-boot:run
```

默认访问地址：

```text
http://localhost:8080
```

### 5. 编译检查

```bash
./mvnw -DskipTests compile
```

## 配置说明

项目支持通过环境变量覆盖核心配置，适合本地开发、测试环境和生产环境分别配置。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端服务端口 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/financial_ai` | PostgreSQL JDBC 地址 |
| `DB_USERNAME` | `postgres` | 数据库用户名 |
| `DB_PASSWORD` | `123456` | 数据库密码 |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `JWT_SECRET` | 开发默认值 | JWT 签名密钥 |
| `JWT_EXPIRATION_HOURS` | `12` | Token 有效小时数 |
| `LIBREOFFICE_COMMAND` | `soffice` | LibreOffice 命令路径 |
| `OFFICE_PREVIEW_TIMEOUT_SECONDS` | `60` | Office 转 PDF 超时时间 |
| `AI_BASE_URL` | `https://api.openai.com/v1` | 默认 Chat API 基础地址 |
| `AI_API_KEY` | 空 | 默认模型 API Key |
| `AI_CHAT_MODEL` | `gpt-4.1-mini` | 默认 Chat 模型 |
| `AI_EMBEDDING_MODEL` | `text-embedding-3-small` | 默认 Embedding 模型 |
| `AI_EMBEDDING_DIMENSIONS` | `1536` | 默认 Embedding 维度 |

> 生产环境必须覆盖 `JWT_SECRET`，并且不要把任何真实 API Key、MinIO Secret、数据库密码提交到 GitHub。

## 默认账号

Flyway 初始化脚本会创建一个超级管理员账号：

```text
用户名：admin
密码：Admin@123
```

该账号拥有全部菜单和接口权限，建议首次部署后立即修改密码。

## 接口文档

启动后访问：

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON：

```text
http://localhost:8080/v3/api-docs
```

认证方式：

1. 调用 `/api/auth/login` 获取 `token`。
2. 打开 Swagger 右上角 `Authorize`。
3. 填入登录返回的 JWT Token。
4. 登录接口无需认证，其余业务接口默认需要认证。

## 核心模块说明

### 身份认证

对应控制器：`AuthController`

- `POST /api/auth/login`：登录。
- `POST /api/auth/logout`：退出登录。
- `GET /api/auth/me`：查询当前用户信息、角色、权限菜单。

登录安全能力：

- Redis 记录登录失败次数，短时间连续失败会被限制。
- JWT Token 退出后进入 Redis 黑名单。
- 普通用户只允许一台设备在线，后登录设备会挤掉前一设备。
- 超级管理员不受单设备限制。

### 系统管理

对应控制器：`SystemController`

能力包括：

- 用户管理：新增、修改、删除、查询、在线状态。
- 角色管理：角色 CRUD、角色菜单授权。
- 菜单管理：目录、菜单、按钮权限，支持多级菜单。
- 部门管理：部门树、负责人、排序、状态。
- 管理员强制退出用户：`POST /api/system/users/{id}/force-logout`。

权限模型：

- 角色表：`sys_role`
- 菜单表：`sys_menu`
- 用户角色关系：`sys_user_role`
- 角色菜单关系：`sys_role_menu`
- 接口通过 `@PreAuthorize` 进行权限校验。

### 日志管理

对应控制器：`LogController`

- 登录日志：`GET /api/logs/login`
- 操作日志：`GET /api/logs/operation`
- 模型对话日志：`GET /api/logs/chat`
- 删除单条日志：`DELETE /api/logs/{type}/{id}`
- 清理历史日志：`DELETE /api/logs/{type}?beforeDays=90`

操作日志通过过滤器采集接口、耗时、状态码、用户、IP、错误信息等内容。

### 系统监控

对应控制器：`MonitorController`

- `GET /api/monitor/system`

返回指标包括：

- 服务器基本信息
- JVM 内存和运行信息
- 磁盘使用情况
- Redis 状态、版本、客户端、内存、Key 数
- PostgreSQL 状态、连接数、数据库大小

### 文件中心

对应控制器：`FileController`

能力包括：

- 存储设置查询、保存、测试。
- 本地存储和 MinIO/S3 存储切换。
- 文件上传、下载、删除、在线预览。
- Word、Excel、PPT 转 PDF 预览。

常用接口：

- `GET /api/storage/settings`
- `PUT /api/storage/settings`
- `POST /api/storage/settings/test`
- `GET /api/files`
- `POST /api/files`
- `GET /api/files/{id}/download`
- `GET /api/files/{id}/preview`
- `GET /api/files/{id}/preview-pdf`
- `DELETE /api/files/{id}`

默认本地上传目录：

```text
data/uploads
```

Office 转 PDF 依赖 LibreOffice。若系统无法识别 `soffice`，请通过 `LIBREOFFICE_COMMAND` 指定完整路径。

### 工作流

对应控制器：`WorkflowController`

基于 Flowable BPMN 2.0，支持：

- 流程定义列表。
- 创建并部署流程。
- 修改并重新部署流程。
- 删除未运行过的流程定义。
- 发起流程实例。
- 查询流程实例。
- 查询我的待办。
- 审批通过或驳回。

常用接口：

- `GET /api/workflow/definitions`
- `POST /api/workflow/definitions`
- `PUT /api/workflow/definitions/{id}`
- `DELETE /api/workflow/definitions/{id}`
- `POST /api/workflow/instances`
- `GET /api/workflow/instances`
- `GET /api/workflow/tasks`
- `POST /api/workflow/tasks/{id}/handle`

### 即时通讯

对应控制器：`ImController`

能力包括：

- 查询可聊天用户。
- 发起或打开单聊。
- 创建群聊。
- 查询会话列表和消息记录。
- 发送文本、图片、文件消息。
- 未读数统计。
- 消息已读。
- 正在输入状态。
- 两分钟内撤回本人消息。
- 聊天记录搜索。
- 群成员管理、群名修改、置顶、免打扰。
- WebSocket 实时推送。

WebSocket 地址：

```text
ws://localhost:8080/ws/chat?token=JWT_TOKEN
```

### AI 智能中心

对应控制器：`AiController`

能力包括：

- 普通模型对话。
- SSE 流式模型对话。
- 模型会话列表、新建、重命名、删除。
- 会话消息查询。
- 模型配置查询、新增、修改、删除。
- Chat 模型连通性测试。
- Embedding 模型连通性测试。
- 可选挂载知识库进行 RAG 问答。

模型配置表：`ai_model_config`

配置项分为两组：

- Chat 模型配置：`chat_api_url`、`chat_api_key_encrypted`、`chat_model`、`temperature`、`max_tokens`
- Embedding 模型配置：`vector_base_url`、`vector_api_path`、`vector_api_key_encrypted`、`embedding_model`、`embedding_dimensions`、`vector_concurrency`

API Key 会加密后保存，密钥派生自 `JWT_SECRET`。生产环境必须固定且妥善保管 `JWT_SECRET`，否则已加密配置可能无法解密。

## RAG 知识库说明

对应控制器：`KnowledgeController`

RAG 知识库模块用于把企业文档解析、切片、向量化，并在对话时进行知识库增强回答。

### 数据模型

核心表：

- `knowledge_base`：知识库集合。
- `knowledge_document`：知识库文档，关联文件中心 `sys_file`。
- `knowledge_chunk`：文档切片，包含父块、子块、页码、Token 数、Embedding、全文检索向量。

切片表使用 pgvector HNSW 索引：

```sql
CREATE INDEX idx_knowledge_chunk_embedding_hnsw
ON knowledge_chunk
USING HNSW (embedding vector_cosine_ops)
WHERE embedding IS NOT NULL;
```

同时使用 PostgreSQL GIN 全文索引：

```sql
CREATE INDEX idx_knowledge_chunk_search_vector
ON knowledge_chunk
USING GIN(search_vector);
```

### 文档处理流程

```text
上传文档
  |
  v
写入 sys_file
  |
  v
创建 knowledge_document，状态 PENDING
  |
  v
后台异步解析文档，状态 PROCESSING
  |
  v
生成 Parent Chunk
  |
  v
生成 Child Chunk
  |
  v
调用 Embedding API
  |
  v
写入 pgvector
  |
  v
状态 COMPLETED
```

如果解析或向量化失败，文档状态会更新为 `FAILED`，并保存错误信息，不会导致主服务崩溃。

### 父子切片策略

- Parent Chunk：较大的上下文块，约 800-1000 Tokens，用于最终送入大模型。
- Child Chunk：较小的检索块，约 200-300 Tokens，带 Overlap，用于向量化和精准召回。
- 检索时优先召回 Child Chunk，再回溯到 Parent Chunk 作为上下文。

### 混合检索策略

`KnowledgeRetrievalService` 实现了混合检索：

- 向量语义检索：使用 pgvector 余弦相似度。
- 全文关键词检索：使用 PostgreSQL `tsvector` / `tsquery`。
- RRF 融合：使用 Reciprocal Rank Fusion 融合多路召回结果。
- 元数据过滤：支持按知识库 ID 和当前用户权限过滤。

### 知识库问答

`POST /api/ai/chat/stream` 支持传入 `knowledgeBaseIds`。

请求示例：

```json
{
  "message": "请总结这份部署手册的环境要求",
  "conversationId": 1,
  "knowledgeBaseIds": [1]
}
```

指定知识库后，后端会：

1. 对用户问题生成 Query Embedding。
2. 通过混合检索召回相关切片。
3. 组装防幻觉 System Prompt。
4. 调用 Chat 模型进行 SSE 流式回答。
5. 返回答案和引用来源。

防幻觉原则：

- 仅基于参考文档回答。
- 知识库无相关信息时，明确说明无法回答。
- 引用来源按编号返回，前端可聚合展示为“第 1、3、5、7 页”。

## 数据库迁移

项目使用 Flyway 自动管理数据库结构，迁移脚本位于：

```text
src/main/resources/db/migration
```

当前主要迁移：

| 版本 | 说明 |
| --- | --- |
| V1 | 初始化系统管理、用户、角色、菜单、AI 基础知识表 |
| V2 | AI 模型配置 |
| V3 | Chat 模型和向量模型配置拆分 |
| V4 | 登录日志、操作日志、模型对话日志 |
| V5 | 系统监控菜单 |
| V6 | 文件中心、存储配置、工作流基础表 |
| V7 | 系统监控移动到系统管理 |
| V8 | 文件设置菜单调整 |
| V9 | 文件设置移动到系统管理 |
| V10 | Flowable BPMN 元数据 |
| V11 | 模型设置、日志管理移动到系统管理 |
| V12 | 企业即时通讯 |
| V13 | AI 会话历史 |
| V14 | RAG 知识库增强、父子切片、HNSW、全文索引 |

首次启动时 Flyway 会自动执行迁移。已有数据库升级时请勿手动修改 `flyway_schema_history`。

## 常用接口

| 模块 | 路径 |
| --- | --- |
| 登录 | `POST /api/auth/login` |
| 当前用户 | `GET /api/auth/me` |
| 用户管理 | `/api/system/users` |
| 角色管理 | `/api/system/roles` |
| 菜单管理 | `/api/system/menus` |
| 部门管理 | `/api/system/departments` |
| 系统监控 | `GET /api/monitor/system` |
| 文件中心 | `/api/files` |
| 存储设置 | `/api/storage/settings` |
| 工作流 | `/api/workflow/**` |
| 即时通讯 | `/api/im/**` |
| 模型对话 | `/api/ai/chat` |
| 流式对话 | `/api/ai/chat/stream` |
| 模型设置 | `/api/ai/settings` |
| 知识库 | `/api/knowledge/**` |
| 日志管理 | `/api/logs/**` |

完整接口以 Swagger UI 为准。

## 开发规范

- Java 版本：21。
- 框架版本：Spring Boot 3.5.x。
- 新接口请统一返回 `ApiResponse`。
- 新接口请添加中文 `@Operation` 和合适的 `@Tag`。
- 需要权限控制的接口请添加 `@PreAuthorize`。
- 数据库结构变更必须新增 Flyway 脚本，不要直接改已发布迁移。
- 业务异常优先抛出可读的 `IllegalArgumentException`，由全局异常处理器统一返回。
- 密钥类字段不要明文落库，参考 `SecretCryptoService`。
- 文件路径必须做 normalize 和越权校验，避免目录穿越。
- RAG 相关任务失败时应更新状态为 `FAILED`，并保留错误信息，不能影响主应用。

## 安全建议

开源或生产部署前请重点检查：

- 修改默认管理员密码。
- 使用足够强的 `JWT_SECRET`。
- 不要提交真实数据库密码、Redis 密码、模型 API Key、MinIO Secret。
- 生产环境限制 CORS 来源。
- 生产环境建议启用 HTTPS。
- 对公网开放时建议在网关层增加限流。
- 定期清理历史日志和临时预览文件。
- 根据业务需要补充接口级、数据级权限。

## 常见问题

### 1. 启动时报 `extension "vector" is not available`

说明 PostgreSQL 未安装 pgvector。请先安装 pgvector，并确认当前数据库可以执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2. Redis 连接失败

请确认 Redis 正在运行，并检查以下配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 3. Office 文件无法转 PDF 预览

请安装 LibreOffice，并确认命令可执行：

```bash
soffice --version
```

如果命令不在 PATH 中，请设置：

```bash
export LIBREOFFICE_COMMAND=/Applications/LibreOffice.app/Contents/MacOS/soffice
```

### 4. RAG 文档一直是 `FAILED`

常见原因：

- 模型配置中的 Embedding API Key 无效。
- Embedding 模型维度与数据库 `vector(1024)` 不一致。
- 上传文件内容无法解析。
- 网络无法访问模型服务。

请先在管理员后台测试向量化模型，再重新处理文档。

### 5. 普通用户登录后另一个设备被挤下线

这是系统设计。普通用户只允许一台设备在线，超级管理员除外。

## 开源说明

建议开源前补充以下文件：

- `LICENSE`
- `.gitignore`
- `CONTRIBUTING.md`
- `SECURITY.md`
- 示例配置文件，例如 `application-example.yaml`

本项目 README 不包含任何真实密钥。提交 GitHub 前请再次检查：

```bash
git diff --cached
git status
```

如果仓库历史中曾经提交过密钥，请在公开前先轮换密钥，并清理 Git 历史。
