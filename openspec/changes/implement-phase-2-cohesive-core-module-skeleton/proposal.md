## 背景

Phase 0 已冻结关键依赖、协议能力与机器合同，但后续 Adapter、Agent 和交付层仍缺少不可绕过的领域核心、状态所有权与事务边界。现在需要按阶段 02 把稳定规则收敛到 `opspilot-core`，以可独立测试的模块骨架和合同阻止框架、厂商类型及跨层状态语义侵入核心。

## 变更内容

- 补齐设计冻结的 Maven 模块骨架、包级边界、唯一 composition root 和架构依赖护栏。
- 建立冻结身份、值对象、错误合同、四层状态模型及其显式迁移策略。
- 建立版本化 `IncidentAgentState`、字段边界、迁移链、引用校验和禁止内容规则。
- 实现 Application Use Case、Supervisor/Bounded ReAct 策略、预算、checkpoint、CAS、取消、恢复和有界停机语义。
- 定义窄 Provider、Tool、Source、Repository、Artifact、A2A、CodeAnalysis 与 Sandbox Port，并建立唯一 `EvidenceNormalizer` 边界。
- 固定安全调用中间件顺序、短路和 fail-closed 语义。
- 定义原子 checkpoint、事务 outbox、提交后投影与幂等消费合同，并提供仅测试可用的内存实现。
- 建立核心验收套件和边界证据，证明核心无需 Spring、数据库、AgentScope 或 A2A HTTP 即可验证。
- 不在本变更中实现真实持久化、真实 Provider、AgentScope 映射、A2A HTTP、产品 Controller、万能 Registry、运行时热加载或内存 Event Bus 业务 RPC。

## 能力

### 新增能力

- `module-dependency-guardrails`：冻结物理模块、包边界、composition root 与非法依赖检测。
- `domain-identity-error-contracts`：强类型身份、组合身份、`rootCauseCode` 与稳定错误链合同。
- `four-layer-state-machines`：Agent endpoint、A2A Task、step attempt 和 Incident Run 的分层状态及权威迁移。
- `incident-agent-state-contract`：版本化状态快照、显式迁移、边界限制、引用完整性和状态平面所有权。
- `bounded-supervisor-use-cases`：核心 Use Case、有界调度、预算、无进展停机、取消与恢复策略。
- `core-ports-evidence-boundary`：窄 Port、CodeSnapshot 边界、唯一 Evidence 规范化入口和扩展描述合同。
- `secure-invocation-middleware`：固定安全中间件链、权限交集、审批、预算、脱敏和审计语义。
- `transactional-checkpoint-outbox`：UnitOfWork、CAS、原子 checkpoint、事务 outbox 与幂等投影。
- `core-acceptance-boundary-evidence`：核心专项验收、恢复链、依赖扫描和边界所有权证据。

### 修改能力

无。仓库当前没有已归档的 OpenSpec 基线能力，本变更只新增阶段 02 能力规范。

## 影响范围

- 代码与构建：根/子模块 `pom.xml`、冻结 Maven 模块、`opspilot-core` 包结构、`opspilot-server` composition root 骨架与架构测试。
- 领域与应用：身份、值对象、错误、状态机、`IncidentAgentState`、Use Case、调度/预算/取消策略及领域事件。
- 扩展边界：Provider、Tool、Source、Repository、Artifact、A2A、CodeSource/CodeAnalysis、Sandbox Port 与 Evidence 规范化。
- 安全与事务：固定中间件、UnitOfWork、checkpoint、CAS、outbox、提交后投影和仅测试内存实现。
- 验证与证据：参数化领域测试、架构测试、事务/恢复测试、依赖树与字节码扫描报告。
- 前置条件：阶段 01 门禁和第 23.8 节 Definition of Ready 必须满足；若机器合同与阶段文档冲突，以设计事实源为准并先修正规划。
