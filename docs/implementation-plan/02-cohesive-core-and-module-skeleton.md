# 阶段 02：内聚核心、模块骨架与状态机

## 目标

把系统最稳定的领域规则、应用用例和 Port 收敛到 `opspilot-core`，建立第 28 章规定的依赖方向、状态所有权、事务/outbox 语义和测试实现，为后续 Adapter、Agent 与交付层提供不可绕过的核心边界。

## 前置门禁

- 阶段 01 全部通过；关键依赖版本、协议能力和机器合同已经冻结。
- 所实现模块满足第 23.8 节 Definition of Ready。

## 建设范围

1. 按设计建立 `opspilot-core`、`opspilot-tools-default`、`opspilot-agent-runtime-agentscope`、`opspilot-a2a`、`opspilot-adapters/*`、`opspilot-server`、`opspilot-evaluation`、`sample-system`、`fault-lab` 和 `deployment`；不为单个接口增加 Maven 模块。
2. 在 core 中实现 Incident、Run、step/attempt、Evidence、Hypothesis、RCA、Remediation、Approval、Artifact、ChainFailure 等领域对象和第 23.2 节冻结 ID；这些 ID 全局使用字符串 UUID，API 不暴露数据库自增键。`rootCauseCode` 使用稳定的小写点分代码，未知/无法收敛时为 `null`，禁止生成临时代码。
3. 原样实现第 5.7–5.8、6.1–6.4 节的 A2A Task、Agent endpoint、step attempt、Incident Run 状态、合法转换和不变量；四层状态保持分离，终态不可离开。A2A `INPUT_REQUIRED/AUTH_REQUIRED/COMPLETED` 分别映射本地 `WAITING_INPUT/WAITING_AUTH/VALIDATING_RESULT`，Artifact 校验通过后 step 才能完成。Endpoint 覆盖 `UNKNOWN/PROBING/READY/UNAVAILABLE/DRAINING/DISABLED` 及设计规定的 reasonCode/retryable/Card digest/探针时间，端点变化不篡改既有 Task，单个 Task 失败也不能直接判定端点不可用。
4. 实现 `IncidentAgentState` 的版本化 JSON 合同、字段/列表边界和显式迁移；未知版本返回 `STATE_SCHEMA_UNSUPPORTED`，缺失或跨 Run 的必需引用返回 `STATE_REFERENCE_INVALID`，禁止猜测、丢弃字段或把日志/Trace/代码/知识正文、完整 Prompt/Response、隐藏推理、密钥、Ground Truth 和大 Artifact 放入快照。
5. 实现 Supervisor/Use Case、Bounded ReAct 策略、checkpoint、CAS、取消、恢复、预算和停机规则。core 只定义策略，不实现第二套 Agent 循环。
6. 定义窄 Port：Chat/Embedding/Rerank、Tool、ObservabilitySource、CodeSource、Repository/UnitOfWork、Artifact、A2A Client、CodeAnalysis、SandboxRunner；Provider 三接口保持分离，CodeSource 只负责把多代码托管平台统一为不可变 CodeSnapshot，CodeAnalysis 只分析已物化的只读快照。
7. 实现唯一 `EvidenceNormalizer` 边界和领域校验：Diagnosis/Hypothesis/RCA 只接受 Evidence ID；ObservationBatch、CodeFinding、KnowledgeResult 和厂商 DTO 不能进入核心分析输入。
8. 实现固定中间件顺序：`Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit`；安全步骤不可移除或重排。
9. 实现事务 checkpoint 契约：状态/乐观锁、Tool/Model/A2A 审计、Evidence/Hypothesis/Artifact 绑定和 outbox 同事务；Domain Event 只能在提交后投影。
10. 为 core Port 提供仅测试可用的内存实现，证明核心状态机和恢复逻辑可独立测试；这些实现不得注册为可部署 Provider 或生产回退。
11. 建立架构测试，固定 `core ← tools-default / agent-runtime / a2a / adapters / evaluation ← server`，阻止 Spring、AgentScope、A2A SDK、JPA 和厂商库进入 core。

## 详细实施计划

### 02-WP01：物理模块与依赖护栏

**输入：**阶段 01 锁定的根工程、第 4.1–4.2、28.3、28.6 节。

**代码/配置落点：**各 Maven 模块 `pom.xml`、core 包结构、架构测试、`opspilot-server` composition root 骨架。

**任务：**

1. `02-WP01.T01` 补齐设计规定的模块目录和父子 POM 依赖管理；`opspilot-adapters` 只作为聚合目录，不成为互相调用的大模块。
2. `02-WP01.T02` 在 `opspilot-core` 建立 `domain/application/port/policy` 包；在 A2A 模块建立 `contract/client/server` 包级边界。
3. `02-WP01.T03` 建立 ArchUnit 或等价规则，禁止 core 导入 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 和厂商 SDK。
4. `02-WP01.T04` 禁止 Adapter 实现间依赖、Adapter 反向调用 Server、专业 Agent 依赖 Supervisor Repository，以及非 Server 模块承担 composition root。
5. `02-WP01.T05` 增加“单个接口不得新增模块”的模块清单测试或构建审查规则。

**验证：**刻意加入每类非法依赖时架构测试失败；正常骨架执行根 Maven `verify` 通过。

### 02-WP02：冻结身份、值对象与错误合同

**输入：**第 23.2 节、机器合同中的 UUID/枚举/哈希约束。

**代码/配置落点：**`opspilot-core/domain` 的身份和值对象、稳定错误码、序列化测试。

**任务：**

1. `02-WP02.T01` 为 Incident、Run、step、A2A Task、Evidence、Artifact、Hypothesis 建立强类型身份；除单调递增的 `attempt` 外，对外身份使用字符串 UUID。
2. `02-WP02.T02` 建立 `(runId, stepId, attempt)`、`(remoteAgentId, a2aTaskId)` 等组合身份和值相等规则；`stepId` 在线路上使用 UUID 字符串、Java 领域模型使用 `UUID`、PostgreSQL 使用 `uuid`，禁止可变步骤标题、数组序号或数据库自增键进入身份合同。
3. `02-WP02.T03` 实现 `rootCauseCode` 小写点分格式、已知目录校验和可空语义；未知根因不生成临时代码。
4. `02-WP02.T04` 建立 `ChainFailure`、错误分类、retryable、关联 ID、checkpoint 和受控日志引用类型，敏感 cause 只允许脱敏摘要。
5. `02-WP02.T05` 对 UUID、哈希、rootCauseCode、跨 Run 引用和未知枚举编写边界/负向测试。

**验证：**领域类型与 OpenAPI/JSON Schema 的格式一致；非法身份在进入 Repository/状态机前被拒绝。

### 02-WP03：四层状态模型与权威迁移表

**输入：**第 5.7、5.8、6.2–6.4 节权威矩阵。

**代码/配置落点：**core 状态枚举、迁移 Policy、reasonCode、状态事件和参数化测试。

**任务：**

1. `02-WP03.T01` 分别定义 Agent endpoint、A2A Task 映射、step attempt 和 Incident Run 状态，不复用跨层 `status` 类型。
2. `02-WP03.T02` 把每张权威迁移表编码为显式 allowlist/Policy；同状态进度事件不算迁移，终态无后继。
3. `02-WP03.T03` 实现 `INPUT_REQUIRED → WAITING_INPUT`、`AUTH_REQUIRED → WAITING_AUTH`、`COMPLETED → VALIDATING_RESULT` 映射；Artifact 未校验前 step 不完成。
4. `02-WP03.T04` 实现 attempt 的 `DISPATCHING/RECONCILING/RETRY_SCHEDULED/CANCEL_REQUESTED` 语义；重试创建新 attempt，旧终态不可覆盖。
5. `02-WP03.T05` 实现 endpoint reasonCode、retryable、Card digest、探针时间；端点状态变化不改写 Task，单 Task 失败不直接改变 endpoint。
6. `02-WP03.T06` 实现 Incident `WAITING_INPUT/WAITING_APPROVAL/CANCELLING/GENERATING_REPORT` 的严格分离和顶层 Task/Run 终态映射。

**验证：**以参数化测试覆盖所有允许边和所有状态对的非法边；非法迁移返回 `INVALID_STATE_TRANSITION` 且快照版本不变。

### 02-WP04：IncidentAgentState 合同与迁移

**输入：**第 6.1 节权威字段、状态平面分离规则。

**代码/配置落点：**版本化 `IncidentAgentState` DTO/Schema、迁移器、引用校验器、序列化测试。

**任务：**

1. `02-WP04.T01` 按权威字段分组实现身份、进度、steps/attempts、Evidence/Hypothesis 引用、预算、循环治理、缺失、失败、报告和取消字段。
2. `02-WP04.T02` 为字符串、列表、steps、指纹、warnings 和序列化大小定义来自预算/Profile 的上限，防止快照无界增长。
3. `02-WP04.T03` 实现显式版本迁移链；只接受声明支持的 source/target 版本，未知版本返回 `STATE_SCHEMA_UNSUPPORTED`。
4. `02-WP04.T04` 实现加载时引用验证：必需引用存在、属于同一 Run、类型匹配，否则返回 `STATE_REFERENCE_INVALID` 并停止 Run。
5. `02-WP04.T05` 建立禁止内容扫描/测试，覆盖日志/Trace/代码/知识正文、完整 Evidence、Prompt/Response、隐藏推理、Message history、Tool 原始输出、Secret、Ground Truth 和大 Artifact。
6. `02-WP04.T06` 明确 `IncidentAgentState`、AgentScope state、A2A Task state 三个 Port/所有者，禁止互相覆盖 JSON。

**验证：**每个受支持版本 round-trip；未知版本、未知字段处理、非法引用和禁止内容均 fail closed。

### 02-WP05：Application Use Case 与有界调度策略

**输入：**第 5.3、6.5、6.9、17.4、28.7 节。

**代码/配置落点：**core application service、Supervisor policy、Bounded ReAct policy、checkpoint/恢复接口。

**任务：**

1. `02-WP05.T01` 定义创建/启动/恢复/取消 Incident Run、接收 A2A 结果、生成报告等 Use Case 输入输出，不使用 Controller/SDK DTO。
2. `02-WP05.T02` 实现有限计划、串行 step、step 先持久化再委派、补证回环和 `WAITING_INPUT` 后重新规划规则。
3. `02-WP05.T03` 实现轮数、Agent 调用、Tool、A2A、Token、费用和 deadline 预算；模型不能提高预算。
4. `02-WP05.T04` 实现动作/补证指纹、Evidence novelty、连续无进展和停止优先级；预算耗尽/NO_PROGRESS 进入受限报告而非无限重试。
5. `02-WP05.T05` 实现取消意图 CAS、禁止创建新 step、下游取消收敛和迟到 Artifact 仅审计语义。
6. `02-WP05.T06` 只定义 AgentScope Adapter 所需事件/中断 Port，不在 core 编写第二套业务 `while` loop。

**验证：**使用测试 Port 完成正常、空知识、输入中断、预算耗尽、NO_PROGRESS、关键失败和取消恢复的确定性用例。

### 02-WP06：窄 Port 与唯一 Evidence 边界

**输入：**第 4.5、11–13、27.4、27.7、28.4 节。

**代码/配置落点：**core Port、领域请求/结果、EvidenceNormalizer、provenance 类型和合同测试接口。

**任务：**

1. `02-WP06.T01` 分离 Chat、Embedding、Rerank 三个 Provider Port；定义 deadline、identity、Usage 和错误结果，不暴露厂商协议对象。
2. `02-WP06.T02` 定义 Tool、ObservabilitySource、CodeSource、Repository/UnitOfWork、Artifact、A2A Client、CodeAnalysis、SandboxRunner 窄接口和责任边界；固定 `CodeSourceAdapter → CodeSnapshot → CodeAnalysisPort → CodeFinding`，禁止 Analyzer 访问托管平台凭证/API 或 Agent 宿主机任意路径。
3. `02-WP06.T03` 为 Runtime Observation、CodeFinding、KnowledgeResult 实现唯一 EvidenceNormalizer 入口和统一 provenanceRefs。
4. `02-WP06.T04` 强制 Diagnosis/Hypothesis/RCA 只接受 Evidence ID；未规范化输入在编译边界和运行时 Schema 双重拒绝。
5. `02-WP06.T05` 定义 Tool `SUCCEEDED/EMPTY/DENIED/FAILED`、摘要/Artifact/Evidence 引用及稳定 errorCode；大正文不得作为返回 DTO。
6. `02-WP06.T06` 为五类扩展点定义稳定 descriptor/capability 接口，但不实现通用 Registry 或动态发现。

**验证：**向分析入口传 ObservationBatch、CodeFinding、KnowledgeResult 或厂商 DTO 的测试失败；GitHub/GitLab 测试 Adapter 均只能返回同一个 CodeSnapshot 领域类型，CodeAnalysis 测试实现只接受已校验快照；替换测试 Port 时 core 无修改。

### 02-WP07：固定安全中间件链

**输入：**第 18.3、28.9 节。

**代码/配置落点：**core middleware/policy、调用上下文、审计事件和顺序测试。

**任务：**

1. `02-WP07.T01` 固定 `Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit` 顺序。
2. `02-WP07.T02` 定义短路语义：校验/授权/审批/预算失败不得执行 Port；Normalize/校验失败不得把结果标为成功。
3. `02-WP07.T03` 实现权限交集和 READ_ONLY/CONTROLLED_EXECUTION/HIGH_RISK policy；MVP 对 HIGH_RISK 无条件拒绝。
4. `02-WP07.T04` Audit 在成功、拒绝、失败和取消路径均记录主体、动作指纹、权限、审批、预算、摘要和错误，但不记录 Secret/正文。
5. `02-WP07.T05` 允许 tracing/metrics observer 失败被隔离并记录自身错误；安全中间件异常一律 fail closed。

**验证：**顺序、短路、异常、重复调用和绕过尝试的参数化测试通过；故意重排中间件使测试失败。

### 02-WP08：UnitOfWork、checkpoint 与事务 outbox

**输入：**第 2.3、6.4、28.10 节。

**代码/配置落点：**core UnitOfWork/Repository 合同、checkpoint service、Domain Event/outbox 类型和测试实现。

**任务：**

1. `02-WP08.T01` 定义一次 checkpoint 必须包含的状态/版本、调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox event。
2. `02-WP08.T02` 定义 CAS 冲突重读、重新判定迁移和禁止覆盖写的应用语义。
3. `02-WP08.T03` 区分 Domain Event 事实与命令；事件只在提交后发布，消费者使用 eventId 幂等。
4. `02-WP08.T04` 实现内存测试 UnitOfWork，支持提交、回滚、故障点和重复消费；仅位于测试源码或 test fixture。
5. `02-WP08.T05` 验证回滚不留下状态、审计、绑定或事件，projector 失败不回滚已提交核心状态。

**验证：**事务原子性、提交后可见、回滚、CAS 冲突及重复投影测试全部通过。

### 02-WP09：核心验收套件与边界说明

**输入：**02-WP01–08。

**代码/配置落点：**core 单元测试、架构测试、test fixtures、模块依赖/所有权文档。

**任务：**

1. `02-WP09.T01` 按状态、快照、调度、Evidence、Middleware、事务六组发布独立测试报告。
2. `02-WP09.T02` 运行测试实现完成最小恢复链，证明不依赖 Spring 容器、数据库、AgentScope 或 A2A HTTP。
3. `02-WP09.T03` 扫描 core 依赖树和字节码导入，保存无禁用框架/厂商依赖的证据。
4. `02-WP09.T04` 输出 composition root、Repository 所有权、事务边界和扩展点清单；明确测试实现不得在生产 Profile 注册。

**验证：**根构建和 core 专项构建从干净环境通过；测试报告/依赖证据具有 commit、命令、退出码和 SHA-256。

## 阶段内执行顺序

```text
02-WP01 ─→ 02-WP02 ─→ 02-WP03 ─→ 02-WP04
                  ├─→ 02-WP05
                  ├─→ 02-WP06 ─→ 02-WP07
                  └─→ 02-WP08
全部通过 ─→ 02-WP09
```

## 测试与证据矩阵

| 验证层 | 必测内容 | 失败条件 | 证据 |
|---|---|---|---|
| 架构 | 模块依赖、composition root、禁用 import | core/Adapter 边界被绕过 | ArchUnit 报告、依赖树 |
| 领域 | 身份、四层状态、快照、迁移 | 非法迁移成功、未知状态被忽略 | 参数化单测、Schema 报告 |
| 应用 | 调度、预算、NO_PROGRESS、取消/恢复 | 无限循环、终态复活、关键失败伪完成 | Use Case 测试报告 |
| 安全 | 权限交集、中间件顺序、HIGH_RISK | 未审批执行、步骤可跳过/重排 | 负向安全测试、审计样例 |
| 事务 | checkpoint、CAS、outbox | 部分提交、回滚残留、重复投影 | UnitOfWork/事务测试报告 |

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
