# 阶段 02：内聚核心、模块骨架与状态机

## 目标

把系统最稳定的领域规则、应用用例和 Port 收敛到 `opspilot-core`，建立第 28 章规定的依赖方向、状态所有权、事务/outbox 语义和测试实现，为后续 Adapter、Agent 与交付层提供不可绕过的核心边界。

## 前置门禁

- 阶段 01 全部通过；关键依赖版本、协议能力和机器合同已经冻结。
- 所实现模块满足第 23.8 节 Definition of Ready。

## 实施内容

1. 按设计建立 `opspilot-core`、`opspilot-tools-default`、`opspilot-agent-runtime-agentscope`、`opspilot-a2a`、`opspilot-adapters/*`、`opspilot-server`、`opspilot-evaluation`、`sample-system`、`fault-lab` 和 `deployment`；不为单个接口增加 Maven 模块。
2. 在 core 中实现 Incident、Run、step/attempt、Evidence、Hypothesis、RCA、Remediation、Approval、Artifact、ChainFailure 等领域对象和第 23.2 节冻结 ID；这些 ID 全局使用字符串 UUID，API 不暴露数据库自增键。`rootCauseCode` 使用稳定的小写点分代码，未知/无法收敛时为 `null`，禁止生成临时代码。
3. 原样实现第 5.7–5.8、6.1–6.4 节的 A2A Task、Agent endpoint、step attempt、Incident Run 状态、合法转换和不变量；四层状态保持分离，终态不可离开。A2A `INPUT_REQUIRED/AUTH_REQUIRED/COMPLETED` 分别映射本地 `WAITING_INPUT/WAITING_AUTH/VALIDATING_RESULT`，Artifact 校验通过后 step 才能完成。Endpoint 覆盖 `UNKNOWN/PROBING/READY/UNAVAILABLE/DRAINING/DISABLED` 及设计规定的 reasonCode/retryable/Card digest/探针时间，端点变化不篡改既有 Task，单个 Task 失败也不能直接判定端点不可用。
4. 实现 `IncidentAgentState` 的版本化 JSON 合同、字段/列表边界和显式迁移；未知版本返回 `STATE_SCHEMA_UNSUPPORTED`，缺失或跨 Run 的必需引用返回 `STATE_REFERENCE_INVALID`，禁止猜测、丢弃字段或把日志/Trace/代码/知识正文、完整 Prompt/Response、隐藏推理、密钥、Ground Truth 和大 Artifact 放入快照。
5. 实现 Supervisor/Use Case、Bounded ReAct 策略、checkpoint、CAS、取消、恢复、预算和停机规则。core 只定义策略，不实现第二套 Agent 循环。
6. 定义窄 Port：Chat/Embedding/Rerank、Tool、Source、Repository/UnitOfWork、Artifact、A2A Client、CodeAnalysis、SandboxRunner；Provider 三接口保持分离。
7. 实现唯一 `EvidenceNormalizer` 边界和领域校验：Diagnosis/Hypothesis/RCA 只接受 Evidence ID；ObservationBatch、CodeFinding、KnowledgeResult 和厂商 DTO 不能进入核心分析输入。
8. 实现固定中间件顺序：`Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit`；安全步骤不可移除或重排。
9. 实现事务 checkpoint 契约：状态/乐观锁、Tool/Model/A2A 审计、Evidence/Hypothesis/Artifact 绑定和 outbox 同事务；Domain Event 只能在提交后投影。
10. 为 core Port 提供仅测试可用的内存实现，证明核心状态机和恢复逻辑可独立测试；这些实现不得注册为可部署 Provider 或生产回退。
11. 建立架构测试，固定 `core ← tools-default / agent-runtime / a2a / adapters / evaluation ← server`，阻止 Spring、AgentScope、A2A SDK、JPA 和厂商库进入 core。

## 主要输出

- 完整 Maven 模块骨架和依赖约束；
- core 领域模型、Application Service、Port、Policy、状态机、预算和 outbox 合同；
- 测试实现与领域/架构单元测试；
- ID、状态、错误、领域事件和 Artifact 引用的版本化类型；
- 模块依赖和 composition root 规则说明。

## 完成门禁

- core 的编译依赖中不存在 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 或厂商 SDK。
- 四组状态（Agent endpoint、A2A Task、step attempt、Incident Run）的合法/非法迁移、终态、CAS、取消和恢复测试通过。
- `IncidentAgentState` 序列化/反序列化、显式 Schema 迁移、未知版本/非法引用拒绝、字段/列表上限和禁止内容测试通过；不得丢弃未知状态后继续运行。
- 直接向 Diagnosis/Hypothesis 传入 ObservationBatch、CodeFinding 或 KnowledgeResult 会 fail closed。
- 中间件顺序、短路和安全异常 fail closed 测试通过；HIGH_RISK 在 MVP 中始终不能执行。
- outbox 只在事务提交后可见，回滚不留下状态/审计/事件，重复消费不重复投影。
- 替换测试 Port 实现时 core diff 为零；Adapter 之间无实现依赖，Server 仍是唯一 composition root。

## 明确不做

- 不创建万能 Registry、运行时热加载、classpath 自动发现、任意 Hook 或内存 Event Bus 业务 RPC。
- 不实现真实持久化、真实 Provider、AgentScope 映射、A2A HTTP 或产品 Controller；它们属于后续阶段。

## 设计依据

- [模块与边界](../design/opspilot-system-design/02-modules-and-boundaries.md)
- [A2A 状态与调度](../design/opspilot-system-design/03-a2a-multi-agent-architecture.md)
- [实现合同与演进规则](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
- [内聚核心与受控扩展](../design/opspilot-system-design/17-cohesive-core-and-controlled-extensions.md)
