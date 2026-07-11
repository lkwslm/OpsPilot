## 4. 模块划分与职责

### 4.1 建议仓库结构

```text
opspilot/
├── pom.xml
├── README.md
├── docs/
│   ├── design/
│   └── adr/
├── opspilot-domain/
├── opspilot-model-provider/
├── opspilot-agent-core/
├── opspilot-agent-adapter-agentscope/
├── opspilot-a2a-contract/
├── opspilot-a2a-client/
├── opspilot-a2a-server-adapter/
├── opspilot-tool-api/
├── opspilot-tool-impl/
├── opspilot-rag/
├── opspilot-evaluation/
├── opspilot-server/
├── sample-system/
│   ├── sample-gateway/
│   ├── order-service/
│   ├── inventory-service/
│   └── shared-observability/
├── fault-lab/
│   ├── scenario-runner/
│   ├── scenarios/
│   ├── load-tests/
│   ├── collectors/
│   └── generated-dataset/
├── deployment/
│   ├── docker-compose.yml
│   ├── postgres/
│   ├── models/
│   ├── prometheus/
│   ├── otel-collector/
│   ├── jaeger/
│   └── toxiproxy/
└── scripts/
```

部署结构不包含 `deployment/mysql/` 和 `deployment/qdrant/`。`opspilot-model-provider` 为三类模型提供统一 SPI 和 HTTP Adapter；Provider 数量显著增加时再拆分实现模块。

### 4.2 Java 模块职责与依赖

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `opspilot-domain` | Incident、Evidence、Hypothesis、Remediation、Approval、Artifact、Evaluation 等纯领域对象和规则 | JDK/轻量校验，不依赖 Spring |
| `opspilot-model-provider` | Chat/Embedding/Rerank SPI、配置解析、OpenAI-Compatible/Infinity HTTP Adapter、能力校验 | HTTP/JSON；不依赖业务模块 |
| `opspilot-tool-api` | Tool SPI、Schema、权限等级、执行上下文和结果 | domain |
| `opspilot-agent-core` | `BoundedReActRunner` 策略包装器、状态机、调度器、Context Builder、Token Budget、Agent 结果协议、Repository Port | domain、tool-api、model-provider |
| `opspilot-agent-adapter-agentscope` | 将 6 个 Agent、工具和结构化输出接入实际 AgentScope Java API | agent-core、tool-api、model-provider |
| `opspilot-a2a-contract` | 锁定 A2A 1.0 协议模型、OpsPilot Artifact Schema/媒体类型和版本兼容规则；优先使用锁定官方 proto/SDK 生成对象 | 不依赖业务实现 |
| `opspilot-a2a-client` | Agent Card 获取/校验、受信目录、send/stream/get/cancel/subscribe、鉴权、恢复、幂等和协议错误映射 | a2a-contract、domain port |
| `opspilot-a2a-server-adapter` | 为 6 个 Agent 暴露 Agent Card 和 A2A HTTP+JSON Server，将协议对象映射到 agent-core | a2a-contract、agent-core |
| `opspilot-tool-impl` | 日志、Prometheus、Jaeger、健康、配置、代码、知识、沙箱工具实现与中间件 | tool-api、rag、基础设施客户端 |
| `opspilot-rag` | 文档导入、Chunk、向量版本、召回、元数据过滤、重排和引用 | domain、model-provider、PostgreSQL |
| `opspilot-evaluation` | 读取隔离 Ground Truth，计算 8 类指标，生成 JSON/Markdown 报告 | domain；使用同一 PostgreSQL 中独立 schema/角色及独立 Artifact 凭证 |
| `opspilot-server` | REST/SSE、参数校验、错误映射、事务装配、配置启动校验 | 上述应用模块 |

推荐依赖方向：

```text
domain ← tool-api / model-provider / a2a-contract
       ← agent-core / rag / a2a-client
       ← agentscope-adapter / a2a-server-adapter / tool-impl / evaluation
       ← server
```

禁止循环依赖；AgentScope 和 A2A SDK 类型不能出现在 domain、tool-api 或产品 REST DTO 中。专业 Agent 不得依赖 Supervisor 的状态 Repository，也不得直接写 Supervisor 所有的 Incident、Hypothesis 或 RCA 表。

### 4.3 被测业务系统

- `sample-gateway`：提供统一外部 API、调用 `order-service`、记录 Trace/请求指标、映射下游错误。
- `order-service`：创建/查询订单，调用 `inventory-service`，使用 PostgreSQL 与 HikariCP，保留线程池、异步任务和测试故障开关。
- `inventory-service`：查询/预占库存，支持延迟、异常、线程阻塞故障开关；MVP 不使用 Redis 缓存。

业务接口保留：

```http
POST /api/orders
GET  /api/orders/{id}
GET  /api/inventory/{sku}
POST /api/inventory/{sku}/reserve
```

仅 `fault-lab`/`test` Profile 暴露：

```http
POST /internal/faults/{faultName}/enable
POST /internal/faults/{faultName}/disable
GET  /internal/faults
```

生产 Profile 不注册上述 Controller，并在启动测试中验证其路由不存在。

### 4.4 Fault Lab 与数据集

核心组件保留为 `ScenarioLoader`、`EnvironmentController`、`HealthChecker`、`LoadGenerator`、`FaultInjector`、`ArtifactCollector`、`TicketGenerator`、`GroundTruthGenerator`、`DatasetWriter`、`ScenarioValidator`。场景步骤按“重置—健康—基线—注入—负载—采集—恢复—导出—校验”执行；任何失败都进入 `finally` 恢复环境。

数据集目录保留：

```text
fault-lab/generated-dataset/{scenario-id}/
├── input/                 # Agent 只读
│   ├── ticket.md
│   ├── metadata.json
│   ├── logs/
│   ├── metrics/
│   ├── traces/
│   ├── configs/
│   └── code-context/
├── ground-truth/          # 仅 fault-lab/evaluation
└── execution/             # 运行日志与时间线，敏感部分不可给 Agent
```

数据库为每个 Artifact 保存 `artifact_id`、Incident/Scenario 关联、受控 URI、SHA-256、大小、媒体类型、访问级别和创建时间。Artifact 服务解析 URI 后再次校验根目录，禁止 `..`、符号链接逃逸和任意绝对路径。

### 4.5 Tool Runtime

```java
public interface AgentTool<I, O> {
    ToolDefinition definition();
    ToolExecutionResult<O> execute(I input, ToolExecutionContext context);
}
```

`ToolDefinition` 至少声明名称、描述、输入/输出 Schema、权限等级、只读标志、超时、可重试条件、最大返回量和审批要求。首期工具及边界：

| 工具 | 首期实现 | 权限 |
|---|---|---|
| `LogSearchTool` | 读取受控 JSONL，按服务/时间/级别/关键字筛选并限条 | READ_ONLY |
| `PrometheusQueryTool` | Instant/Range Query，PromQL 白名单、采样点与超时限制 | READ_ONLY |
| `TraceSearchTool` | Jaeger HTTP API 或受控 Trace JSON | READ_ONLY |
| `ServiceHealthTool` | Actuator、容器状态、HTTP 连通性 | READ_ONLY |
| `ConfigReadTool` | 只返回脱敏配置，禁止密码/Token/Key/完整凭证 | READ_ONLY |
| `CodeSearchTool` | 受限根目录的文件/类/方法/关键字搜索，返回行号与上下文 | READ_ONLY |
| `KnowledgeSearchTool` | PostgreSQL + pgvector 召回并调用统一 RerankProvider | READ_ONLY |
| `SandboxTestTool` | 仅执行配置白名单中的 Maven 测试 | CONTROLLED_EXECUTION |

`HIGH_RISK`（改代码、改配置、Git、任意 Shell、改数据库、生产操作）在 MVP 禁止执行；审批记录不等于自动放开任意命令。

### 4.6 Agent API 与 SSE

产品北向 API：

```http
POST /api/incidents
GET  /api/incidents/{incidentId}
POST /api/incidents/{incidentId}/run
POST /api/incidents/{incidentId}/resume
POST /api/incidents/{incidentId}/cancel
GET  /api/incidents/{incidentId}/state
GET  /api/incidents/{incidentId}/report
GET  /api/incidents/{incidentId}/events
GET  /api/incidents/{incidentId}/tool-calls
POST /api/incidents/{incidentId}/approvals/{approvalId}
```

所有写操作支持 `Idempotency-Key`；响应携带 `requestId`、`incidentId` 和适用时的 `runId`。`state/report/events/tool-calls` 默认读取该 Incident 当前活跃 Run，否则读取最近 Run；可用 `?runId=` 精确指定，服务端必须校验该 Run 属于路径中的 Incident。SSE 事件包括 `STATE_CHANGED`、`PLAN_CREATED`、`AGENT_STARTED`、`AGENT_COMPLETED`、`TOOL_CALL_STARTED`、`TOOL_CALL_COMPLETED`、`EVIDENCE_ADDED`、`HYPOTHESIS_ADDED`、`APPROVAL_REQUIRED`、`REPORT_GENERATED`、`ERROR`。事件 ID 来自 PostgreSQL `incident_event.event_id`，查询通过 `incident_run` 约束 Incident/Run 边界，客户端以 `Last-Event-ID` 断线续传。

这些 REST/SSE 接口面向用户和 Fault Lab，不是 Agent 间协议。Agent 间接口必须使用第 5 章定义的 A2A HTTP+JSON 操作；产品 SSE 可以投影 A2A Task 状态，但不得把自定义事件冒充 A2A stream。

### 4.7 RCA 与评测

RCA JSON 至少包含 Incident、摘要、严重度、`InvestigationOutcome`、可空的 Top-1 根因/置信度/组件、证据充分性、支持与冲突证据、缺失证据、不可用能力、已执行检查、调查时间线、短期动作、长期动作、监控改进、测试建议、人工下一步、回滚、限制和引用。`INCONCLUSIVE` 时 `rootCause` 必须允许为 `null`。Markdown 使用同一领域结果渲染，不能单独让模型生成两个彼此矛盾的版本。

Evaluation 读取最终结果、隔离 Ground Truth、工具记录和状态记录，计算：RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency（轮数、工具数、Token、耗时）和 CitationValidity。首期不使用 LLM-as-a-Judge。
