# 阶段 06：AgentScope Runtime、六个 Agent、A2A 与产品 API

## 目标

实现统一 AgentScope 执行服务、六个受版本控制的 AgentProfile、九个 Tool、A2A 1.0 Client/Server/Task Store、Supervisor 编排，以及符合 OpenAPI 的 REST/SSE 交付层；官方 release/SDK 或官方 proto 生成对象锁定为 `v1.0.1`。

## 前置门禁

- core 状态机/Port、安全链和 outbox 已稳定；真实 Source、Provider、RAG 和持久化可用。
- 使用 Phase 0 验证过的 AgentScope/A2A API，不绕过 Adapter 或网络边界。

## 实施内容

1. 实现一个 `AgentExecutionService` 和 AgentScope Adapter；六角色共享框架内置 `ReActAgent` loop，BoundedReAct 只通过配置/Middleware/事件/中断施加轮数、预算、deadline、取消、checkpoint、重复指纹和 NO_PROGRESS。
2. 按 `agent-profile.schema.json` 实现 Supervisor、EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation Profile；冻结 role/skill、Prompt、Tool/A2A 白名单、Schema、预算、完成条件、沙箱和安全能力闭包。有效权限严格取“平台安全基线 ∩ 服务身份权限 ∩ AgentProfile ∩ 单次任务约束 ∩ 人工审批”；Profile 必须设置 `factsFromEvidenceOnly=true`，只保存逻辑 `modelProfileRef`，不得携带 Provider URL/API Key，也不能放宽 Ground Truth、Secret、任意命令、代码修改或平台安全基线。
3. 补齐九个 Tool：日志、指标、Trace、健康、拓扑、配置、代码、知识库、沙箱测试，并实现显式装配后冻结的 `ToolRegistry`；Tool 返回摘要和 Evidence/Artifact 引用，状态只为 `SUCCEEDED/EMPTY/DENIED/FAILED`。
4. 实现 Java Code Analyzer 与 Maven Sandbox Adapter，以及专用 `CodeAnalyzerRegistry`、`SandboxRunnerRegistry`；CodeFinding 必须经 `normalizeCode` 成为 Evidence，Sandbox 只允许审批后的白名单测试，HIGH_RISK/任意 Shell/代码写入始终禁止。
5. 实现 A2A 1.0 HTTP+JSON 的 Card、Message、Task、Artifact、`send/stream/get/cancel/subscribe`、版本/媒体类型/身份/skill/required extension 校验、messageId 幂等和终态规则；协议请求使用 `Content-Type: application/a2a+json`，Card `protocolVersion` 与 `A2A-Version` 固定为 `1.0`，实现依赖锁定到已验证的官方 `v1.0.1`。覆盖全部 TaskState，`UNSPECIFIED` 必须作为 `InvalidAgentResponse` 拒绝且不得持久化为有效状态；可靠最终结果只能使用完整 Artifact，状态 Message 只用于进度、澄清和限制说明。
6. 按冻结拓扑运行 8080 Supervisor/产品服务和 8081–8085 五专业服务；各进程使用独立身份、不同且按 skill 限权的 Token、A2A base URL 和数据库角色。产品端口只绑定宿主 `127.0.0.1:8080`，专业端口只在 Compose 内网，不内嵌进 `opspilot-server`；每个服务在自己的 origin 暴露唯一 `/.well-known/agent-card.json`。
7. 实现只读、进入镜像/部署清单并计算摘要的 Agent Directory，以及 AgentEndpointState 探针、Card digest、capability snapshot 和只选择 READY endpoint；模型不能动态发现 URL。按第 16.1 节顺序完成启动校验，并提供 liveness、readiness、models、capabilities 健康端点；capability 只报告 `UP|DOWN`，不提供 `DEGRADED` 运行模式。只有数据库/状态持久化、LLM、Embedding、Rerank、Supervisor 与全部专业 A2A skill 有效时 readiness 才为 UP，已完成真实模型探针后的 `KB_EMPTY` 只表示数据状态。
8. 实现 Supervisor 串行委派：先持久 step/messageId，再调用 A2A；接收 Artifact 时固定按“媒体类型 → major schema version → JSON Schema → Source/Resource/Task/Run 归属 → Artifact 哈希 → 引用权限 → 领域不变量”校验，全部通过后才单事务接收，失败不得部分写入；流断先 Get/Subscribe 对账；取消传播；完成后只生成一份结构化 RCA 再渲染 JSON/Markdown。失败/拒绝后的重试必须创建新 attempt、新 messageId 和新 A2A Task，旧 attempt/Task 终态不可复活或覆盖。
9. 严格实现 A2A TaskState ↔ step 映射、step attempt、Incident Run 与 AgentScope session 隔离；Supervisor 使用 `sessionId="supervisor:" + runId`，专业 Agent 使用 `sessionId=serverAgentId + ":" + a2aTaskId`，MVP `userId` 固定为 `opspilot-system`，continuation 复用会话而新 attempt 不继承失败上下文。专业 Agent 不写 Supervisor 状态，恢复时以本地 binding + 远端 Task 对账。A2A `AUTH_REQUIRED` 只表示认证挑战，不能映射成业务 `WAITING_APPROVAL`；顶层 Supervisor Task 必须与 Run 的 `COMPLETED/FAILED/CANCELLED` 分别收敛到 `COMPLETED/FAILED/CANCELED`。
10. 实现北向 `/api` REST/SSE：完整实现 OpenAPI 中的所有操作；所有写请求要求 Idempotency-Key，作用域为 `principal + operation + key`，同 Key 同请求重放原响应、不同请求哈希返回冲突；统一 ErrorResponse/requestId、201/202/400/404/409 语义、runId 归属、报告未就绪 409、SSE 持久事件重放。
11. 实现错误关联：REST、SSE、A2A status、审计和日志共享 requestId/traceId/runId/stepId/a2aTaskId/invocationId 与受控 logArtifactId。

## 主要输出

- AgentScope runtime Adapter、六个 AgentProfile/Prompt/映射器；
- 九个 Tool、Java Code Analyzer、Maven Sandbox 和各专用 Registry；
- A2A contract/client/server、Agent Directory、Card、Task Store 和恢复逻辑；
- 六进程 Compose 配置与服务身份/数据库隔离；
- 产品 REST/SSE Controller、DTO 映射、幂等和统一错误；
- Agent/A2A/API 的合同、状态、恢复和安全测试。

## 完成门禁

- 六张 Card 的 `protocolVersion: "1.0"` 与 skill/媒体类型/streaming/安全声明通过官方 v1.0 Schema 和锁定的 v1.0.1 SDK/对象合同，所有 A2A 操作、全部 TaskState、`UNSPECIFIED`、未知 required extension 和非法终态续写测试通过。
- 抓包证明 Supervisor 到专业 Agent 只走 HTTP+JSON；禁用进程内调用后仍可完成纵切。
- 任一专业 Agent 重启、stream 中断、客户端重启、取消竞争、CAS 冲突后可对账恢复且不重复副作用。
- 重试新建 attempt/messageId/Task、认证与审批隔离、顶层 Task/Run 终态收敛均通过自动化状态测试。
- 六角色动作只能来自 Profile 白名单；中止后不再调用 Tool/Model；没有第二套业务 `while` loop。
- 六个 AgentProfile 均通过 Schema、Registry 能力闭包和权限交集测试，不携带密钥/Provider URL，也不能扩大平台安全基线。
- Agent Directory 摘要、六个独立 origin 的 Card 与四类健康端点通过合同测试；任一 required 能力失效时 readiness DOWN，liveness 不因临时外部不可达而伪报进程死亡。
- Diagnosis/Hypothesis 只收到 Evidence ID；CodeFinding/KnowledgeResult/Observation 直传会被拒绝。
- API/SSE 完整符合 OpenAPI 和事件 Schema；幂等冲突、跨 Run 查询、Last-Event-ID 越权均被拒绝。
- 知识 `KB_EMPTY/NO_MATCH` 可继续现场调查；LLM/A2A/Tool/Embedding/Rerank 技术失败按第 17.4 节结束或形成允许的 `missingEvidence`，绝不伪装完成。

## 明确不做

- 不开放专业 Agent 自由对话、任意级联委派或并行子 Agent。
- 不实现自动 Patch、Git、任意命令、生产动作或可视化审批前端。

## 设计依据

- [A2A 多 Agent 架构](../design/opspilot-system-design/03-a2a-multi-agent-architecture.md)
- [可靠性、安全与可观测性](../design/opspilot-system-design/08-reliability-security-and-observability.md)
- [北向/A2A/Tool 合同](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
- [运行拓扑](../design/opspilot-system-design/13-runtime-topology-and-phase0-gates.md)
