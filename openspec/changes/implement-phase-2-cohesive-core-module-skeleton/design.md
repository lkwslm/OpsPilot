## 背景

Phase 0 已建立并验证根工程、冻结模块和关键机器合同，但现有最小纵切仍不足以承载后续完整 Adapter、Agent 与交付能力。阶段 02 必须把稳定领域规则、应用用例、状态所有权和 Port 收敛到 `opspilot-core`，同时保持设计第 28 章的依赖方向。实现的事实源依次为机器合同、状态矩阵与不变量、模块边界正文和阶段计划；发现冲突时先修正规划，不在代码中选择性解释。

本变更横跨 Maven 模块、领域模型、应用策略、安全调用链和事务合同。前置条件是阶段 01 全部门禁通过且工作包满足 Definition of Ready；实现结果必须在不启动 Spring、PostgreSQL、AgentScope 或 A2A HTTP 的情况下由测试 Port 验证。

## 目标与非目标

**目标：**

- 建立冻结物理模块和 `core ← tools-default / agent-runtime / a2a / adapters / evaluation ← server` 依赖护栏。
- 在 core 内形成身份、错误、四层状态、状态快照、用例、调度、Port、安全和事务的单一事实源。
- 用显式 allowlist、Schema 迁移、CAS、预算和停止策略保证恢复确定性及终态不可复活。
- 用仅测试内存实现、参数化测试、架构测试和依赖证据证明核心边界可独立验收。

**非目标：**

- 不实现真实 PostgreSQL Repository、真实 Provider、AgentScope Adapter 映射、A2A HTTP 或产品 Controller。
- 不创建万能 Registry、运行时热加载、classpath 自动发现、任意 Hook 或内存 Event Bus 业务 RPC。
- 不允许测试实现注册为部署 Provider 或生产回退，也不为单个接口增加 Maven 模块。

## 关键决策

### 1. 以物理模块和架构测试共同固定依赖方向

保留设计冻结的模块清单，`opspilot-adapters` 只作聚合目录，`opspilot-server` 是唯一 composition root；ArchUnit 或等价测试同时检查源码依赖和禁用框架/厂商导入。单靠开发约定无法阻止边界随实现漂移，因此不采用只写模块说明、不做自动检查的方案。

### 2. 四层状态分别建模并使用显式迁移策略

Agent endpoint、A2A Task、step attempt 与 Incident Run 使用不同类型和独立权威迁移表；终态没有后继，同状态进度事件不伪装成迁移。A2A 完成只进入本地结果校验，Artifact 校验通过后 step 才完成。复用一个通用 `status` 会混淆所有者和恢复语义，因此不采用跨层状态枚举。

### 3. 快照只保存有界、可迁移的控制平面状态

`IncidentAgentState` 以版本化 JSON、显式迁移链和同 Run 引用校验承载控制平面；正文、Secret、隐藏推理及大 Artifact 只保留受控引用。未知版本或无效引用 fail closed。为避免字段静默丢失和不可重放，不采用宽松反序列化或“尽力猜测”迁移。

### 4. core 定义有界策略，AgentScope 负责运行循环

Application Use Case 与 Supervisor/Bounded ReAct policy 决定有限计划、预算、补证、取消、恢复和停止优先级；core 只暴露事件/中断 Port，不实现第二套 Agent `while` loop。这样既能确定性单测业务语义，也避免运行时框架与核心出现竞争循环。

### 5. Port 按责任拆窄，Evidence 是分析输入的唯一边界

Chat、Embedding、Rerank 保持三个 Port；CodeSource 只物化不可变 `CodeSnapshot`，CodeAnalysis 只分析已校验快照。ObservationBatch、CodeFinding、KnowledgeResult 统一经过唯一 `EvidenceNormalizer`，Diagnosis/Hypothesis/RCA 只接收 Evidence ID。不采用厂商 DTO 直通或通用 Provider 接口，以免协议耦合及 provenance 分叉。

### 6. 安全中间件链是不可重排的核心策略

调用顺序固定为 `Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit`。前置失败不得执行 Port，安全异常 fail closed；仅 tracing/metrics observer 可隔离自身失败。链条由固定组合而非可变插件列表表达，避免部署配置绕过安全步骤。

### 7. checkpoint 与 outbox 共享一次 UnitOfWork

状态/版本、调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox event 在同一事务 checkpoint 中提交；CAS 冲突必须重读并重新判断迁移，Domain Event 仅在提交后投影且以 `eventId` 幂等。不采用先发布事件或覆盖写，因为二者会产生幽灵事件和终态复活。

### 8. 测试实现与生产装配物理隔离

内存 Repository/UnitOfWork、故障点和重复消费设施只放在测试源码或 test fixture，不进入生产 Profile。核心验收按状态、快照、调度、Evidence、Middleware、事务分别出报告，并绑定 commit、命令、退出码和 SHA-256。

## 工作包依赖

```text
02-WP01 ─→ 02-WP02 ─→ 02-WP03 ─→ 02-WP04
                  ├─→ 02-WP05
                  ├─→ 02-WP06 ─→ 02-WP07
                  └─→ 02-WP08
全部通过 ─→ 02-WP09
```

同一状态、Schema、Migration 或 Workflow 由对应工作包负责最终集成；并行工作不得产生第二事实源。

## 风险与权衡

- **风险：Phase 0 最小实现与阶段 02 冻结边界不一致** → 先以机器合同和设计矩阵核对；需要改变冻结语义时暂停任务并提交设计修订。
- **风险：大量状态对导致遗漏非法边** → 对所有状态对生成参数化测试，允许边使用显式 allowlist，非法边断言版本不变。
- **风险：快照或 Tool 结果无界增长及泄密** → 对字段、列表和序列化大小设 Profile/预算上限，并执行禁止内容与 Secret 扫描。
- **风险：测试实现被误装配到生产** → 放入测试源码/test fixture，并以 composition root/Profile 架构测试禁止生产注册。
- **权衡：九个能力规范与工作包一一对应** → 文档数量增加，但保持任务、需求、验证和责任边界稳定可追溯。

## 落地与回退计划

1. 合入本变更的 OpenSpec 规划，不改变现有运行行为。
2. 依次完成模块护栏、领域身份和四层状态，再并行推进快照、用例、Port 与事务合同。
3. Evidence 边界稳定后实现固定安全中间件，最后汇总核心验收证据。
4. 每个工作包先提交正常、边界和负向测试，再关闭任务；门禁失败时保持未完成并记录阻塞条件。
5. 本阶段无生产数据迁移。回退按工作包撤销代码和测试装配；已生成证据保留并标记失败，不改写历史结果。

## 待闭环问题

- Phase 0 已冻结的状态/错误机器合同是否与阶段 02 文档完全一致？若不一致，必须在实现前按事实源优先级修正规划。
- 现有 Maven 骨架中哪些 Phase 0 Spike 类型需要迁移到测试 fixture，哪些必须删除或由后续 Adapter 接管？该清单应在 `02-WP01` 开始时审查。
