## 4. 模块划分与职责

### 4.1 建议仓库结构

```text
opspilot/
├── pom.xml
├── README.md
├── docs/
│   ├── design/
│   └── adr/
├── opspilot-core/
│   ├── domain/
│   ├── application/
│   └── port/
├── opspilot-tools-default/
├── opspilot-agent-runtime-agentscope/
├── opspilot-a2a/
│   ├── contract/
│   ├── client/
│   └── server/
├── opspilot-adapters/
│   ├── persistence-postgres/
│   ├── model-openai-compatible/
│   ├── retrieval-infinity/
│   ├── knowledge-pgvector/
│   ├── observability/
│   ├── code-java/
│   └── sandbox-maven/
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

部署结构不包含 `deployment/mysql/` 和 `deployment/qdrant/`。v3 收敛物理模块数量：领域、应用服务和稳定 Port 共同组成 `opspilot-core`；A2A 的 contract/client/server 先以包级边界共处一个模块；具体实现位于 `opspilot-adapters` 聚合目录。只有独立发布、真实复用或依赖冲突出现时才继续拆分 Maven 模块，不能因为新增一个接口就新建物理模块。

### 4.2 Java 模块职责与依赖

| 模块 | 高内聚职责 | 允许依赖 |
|---|---|---|
| `opspilot-core` | Incident、Evidence、Hypothesis、Remediation、Approval、Artifact 等领域规则；统一 EvidenceNormalizer；Use Case、Supervisor、状态机、Context Builder、预算、checkpoint；Model/Tool/Source/Repository/A2A 等稳定 Port | JDK/轻量校验；不依赖 Spring、AgentScope、A2A SDK、JPA 或厂商实现 |
| `opspilot-tools-default` | 九个首期 Agent Tool 和固定输入输出映射；只编排 core Port/Normalizer，不包含厂商客户端 | core |
| `opspilot-agent-runtime-agentscope` | 把统一 `AgentExecutionService`、六个受版本控制的 `AgentProfile`、工具调用和结构化输出映射到 AgentScope | core；AgentScope API 只存在于此模块 |
| `opspilot-a2a` | A2A 1.0 协议模型与 Artifact 合同、受信 Client、Server Adapter、Task Store 映射和恢复 | core；A2A SDK/HTTP 类型不能进入 core |
| `opspilot-adapters/*` | PostgreSQL、模型、检索、Source、代码和沙箱等 Port 实现；每个子模块只围绕一种变化原因 | core；可依赖自己的 HTTP/JSON/JDBC/厂商库，禁止 Adapter 间实现依赖 |
| `opspilot-evaluation` | 读取隔离 Ground Truth，计算 8 类指标，生成 JSON/Markdown 报告 | core 的只读合同；独立 schema/角色及 Artifact 凭证 |
| `opspilot-server` | REST/SSE、参数校验、错误映射、配置、专用 Registry 装配和唯一 composition root | core、a2a、agent runtime、选定 adapters |

推荐依赖方向：

```text
core ← tools-default / agent-runtime / a2a / adapters / evaluation ← server
```

模块间默认使用显式类型 Port 直接调用；只有 Agent 逻辑边界使用 A2A，只有事务提交后的多消费者通知使用 Domain Event + outbox。禁止循环依赖、全局 Bean 查找和以 Event Bus 隐藏同步业务依赖。专业 Agent 不得依赖 Supervisor 的状态 Repository，也不得直接写 Supervisor 所有的 Incident、Hypothesis 或 RCA 表。

Provider、Source、Tool、代码分析和沙箱是受控多实现扩展点；状态机、Supervisor、A2A、安全 Policy、Agent 角色发现和 Repository 所有权不是扩展点。完整选择依据和交互规则见第 28 章。

### 4.3 首期参考被测系统

Sample System 只用于首期故障注入和验收。OpsPilot 的目标系统模型、Observation/Evidence、Agent 和 RCA 不得依赖以下 Java/Spring 实现；跨语言与分布式接入规则见第 27 章。

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
| `LogQueryTool` | 通过 `JsonlLogAdapter` 查询；结果转换成 Observation/Evidence | READ_ONLY |
| `MetricQueryTool` | 通过 `PrometheusMetricAdapter` 执行受控模板；Agent 不接触 PromQL | READ_ONLY |
| `TraceQueryTool` | 通过 `JaegerTraceAdapter` 或受控 Trace Adapter 查询 | READ_ONLY |
| `HealthQueryTool` | 通过 Actuator/HTTP/容器健康 Adapter 查询 | READ_ONLY |
| `TopologyQueryTool` | 首期查询静态 Compose 拓扑；返回统一 Resource/Relationship | READ_ONLY |
| `ConfigReadTool` | 只返回脱敏配置，禁止密码/Token/Key/完整凭证 | READ_ONLY |
| `CodeSearchTool` | 首期 `JavaCodeSearchAdapter` 搜索受限根目录；生成语言无关 CodeFinding，再规范化为 `Evidence(signalType=CODE)` | READ_ONLY |
| `KnowledgeSearchTool` | PostgreSQL + pgvector 召回并调用统一 RerankProvider | READ_ONLY |
| `SandboxTestTool` | 首期 `MavenTestAdapter` 仅执行配置白名单测试 | CONTROLLED_EXECUTION |

`HIGH_RISK`（改代码、改配置、Git、任意 Shell、改数据库、生产操作）在 MVP 禁止执行；审批记录不等于自动放开任意命令。

可观测 Tool 必须返回符合第 27 章的 `observationBatchIds/evidenceBundleId`，不能返回厂商 DTO。`CodeSearchTool` 可额外返回 CodeFinding Artifact 供审计，但下游只消费规范化后的 Evidence ID。`KnowledgeSearchTool` 不属于外部可观测 Source；检索引用若要参与诊断也必须先规范化为 Evidence。代码和沙箱使用独立语言 Adapter。新增 Loki、Tempo、Kubernetes、Cloud 或其他语言 Adapter 时，不新增 Agent Tool 名称，除非出现无法由现有能力表达的新安全边界。

`ToolRegistry` 在启动时由 `opspilot-server` 显式注册并冻结。同名 Tool、Schema major 不兼容或默认 `AgentProfile` 依赖的 Tool 缺失时启动失败；不允许 classpath 扫描后“最后一个实现覆盖”。权限、审批、预算和审计中间件由核心按固定顺序包裹 Tool，Tool/Adapter 无权跳过或重排。

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

创建 Incident 必须提供 `targetSystemId`，可选提供一组 `resourceIds` 收紧调查范围；服务端从第 27 章 Target Resource/Source Registry 校验归属和可访问性。工单中的自然语言服务名只作为线索，不能替代稳定 Resource 身份，也不能由模型生成 source URL。

这些 REST/SSE 接口面向用户和 Fault Lab，不是 Agent 间协议。Agent 间接口必须使用第 5 章定义的 A2A HTTP+JSON 操作；产品 SSE 可以投影 A2A Task 状态，但不得把自定义事件冒充 A2A stream。

### 4.7 RCA 与评测

RCA JSON 至少包含 Incident、摘要、严重度、`InvestigationOutcome`、可空的 Top-1 根因/置信度/组件、证据充分性、支持与冲突证据、缺失证据、不可用能力、已执行检查、调查时间线、短期动作、长期动作、监控改进、测试建议、人工下一步、回滚、限制和引用。`INCONCLUSIVE` 时 `rootCause` 必须允许为 `null`。Markdown 使用同一领域结果渲染，不能单独让模型生成两个彼此矛盾的版本。

Evaluation 读取最终结果、隔离 Ground Truth、工具记录和状态记录，计算：RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency（轮数、工具数、Token、耗时）和 CitationValidity。首期不使用 LLM-as-a-Judge。
