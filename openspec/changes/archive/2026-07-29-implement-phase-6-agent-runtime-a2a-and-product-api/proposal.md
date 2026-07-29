## 背景与动机

阶段 00 至 05 已锁定 AgentScope/A2A 互操作门禁，并提供核心状态机、安全调用链、持久化、可观测性与真实模型/RAG 能力，但这些能力尚未收敛为跨进程 Agent 产品链路。阶段 06 需要建立可控、可恢复、可审计的六 Agent 运行时与完整 REST/SSE 交付面，才能让真实 Incident 从北向请求经 A2A 专业分析形成唯一、Evidence-only 的 RCA。

## 变更内容

- 实现唯一 `AgentExecutionService` 与 AgentScope Adapter，以框架 `ReActAgent` loop 配合 Middleware、事件和中断施加轮次、预算、deadline、取消、checkpoint、重复指纹与 `NO_PROGRESS` 约束。
- 按 `agent-profile.schema.json` 固化 Supervisor、EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation 六个 Profile/Prompt/输出 Schema，并计算不可放宽的权限与能力闭包。
- 冻结九个 Tool 的显式 Registry，新增 GitHub/GitLab Code Source、不可变 CodeSnapshot、Java Code Analyzer 与仅允许审批白名单测试的 Maven Sandbox。
- 基于官方 `v1.0.1` 对象实现 A2A 1.0 HTTP+JSON 的 Card、Message、Task、Artifact、`send/stream/get/cancel/subscribe`、持久 Task Store、attempt、幂等、恢复、取消与状态映射。
- 以六个独立进程、服务身份、Token、数据库角色和网络 origin 部署 Supervisor/产品服务与五个专业服务；用只读 Agent Directory、Card digest、endpoint probe 和 capability snapshot 选择 READY endpoint。
- 实现 Supervisor 串行委派、固定顺序的 Artifact 校验、单事务接收、补证回环、analysis seal、唯一结构化 RCA 生成及 JSON/Markdown 渲染。
- 完整实现 `opspilot-v1.yaml` 中的 REST/SSE 操作、写请求幂等、统一错误、Run 归属、报告就绪语义与基于持久事件的断线重放。
- 统一 REST、SSE、A2A、Tool、Provider、审计和日志的关联标识、受控日志 Artifact 与跨边界脱敏。
- 不实现阶段 07 的冻结故障场景，不允许第二套业务 Agent loop、专业 Agent 级联委派、动态 URL 发现、进程内 A2A 快捷路径、任意 Shell、代码写入、HIGH_RISK 动作、未解析 revision 分析或重复 RCA 输入快照。

## 能力范围

### 新增能力

- `agent-execution-runtime`：统一 AgentScope 执行服务、Bounded ReAct 约束、session/checkpoint、事件映射与中止后 fail-closed 行为。
- `agent-profile-capability-closure`：六个版本化 AgentProfile、Prompt、输出 Schema、权限交集、能力闭包与 Evidence-only 安全基线。
- `tool-code-analysis-and-sandbox`：九 Tool 冻结注册、GitHub/GitLab Code Source、不可变 revision/CodeSnapshot、Java Analyzer、Evidence 规范化与 Maven Sandbox 边界。
- `a2a-protocol-runtime`：锁定 A2A v1.0.1 的协议对象、HTTP+JSON 操作、Card/Message/Task/Artifact 校验、幂等和终态规则。
- `a2a-task-attempt-recovery`：本地 step/attempt 与远端 Task binding、状态映射、断流/重启恢复、取消传播和终态收敛。
- `six-agent-service-topology`：六进程网络与身份隔离、每 origin 唯一 Card、只读 Agent Directory、endpoint 状态和 READY 选择。
- `supervisor-evidence-orchestration`：串行委派、Artifact 分层校验与原子接收、补证、analysis seal、唯一 RCA 查询与渲染。
- `service-capability-health`：liveness、readiness、models、capabilities 端点及 required 依赖聚合和 `UP|DOWN` 语义。
- `product-rest-sse-api`：OpenAPI 全操作、写请求幂等、统一错误与状态码、Run 归属及持久 SSE 重放。
- `cross-boundary-correlation-audit`：跨 REST/SSE/A2A/Tool/Provider 的关联标识传播、错误脱敏、受控日志引用和审计查询链。

### 修改能力

- 无。阶段 06 在既有 `agentscope-runtime-gate`、`a2a-interoperability-gate`、`secure-invocation-middleware`、`durable-task-sse-replay`、状态机、Evidence、Artifact 与真实 Provider/RAG 合同之上实现产品能力，不改变这些已冻结需求。

## 影响范围

- 影响 `opspilot-core`、`opspilot-agent-runtime-agentscope`、`opspilot-a2a`、`opspilot-tools-default`、`opspilot-adapters/code-java`、`opspilot-adapters/sandbox-maven`、`opspilot-adapters/persistence-postgres`、`opspilot-server` 与 `deployment` 的运行时、Port/Adapter、持久化、入口和装配。
- 新增或扩展 AgentProfile/Tool/A2A/CodeSnapshot、step attempt/Task binding、Agent Directory/endpoint state、analysis seal、API 幂等与关联审计所需的 Schema、配置、数据库 migration、fixture 和证据输出。
- 运行依赖锁定的 AgentScope API、A2A v1.0.1 官方对象、阶段 05 真实模型与 RAG、GitHub/GitLab 只读连接、PostgreSQL/Artifact 存储及六进程 Compose 内网。
- 验收覆盖 Profile/Tool/A2A/OpenAPI Schema 与合同、全状态和恢复、权限/沙箱/代码源负向测试、六进程抓包和端口扫描、健康对账、SSE 重放、Secret 扫描及带完整关联 ID 的 REST → A2A → Evidence → RCA 纵切。
