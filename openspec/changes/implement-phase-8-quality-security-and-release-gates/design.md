## Context

阶段 07 已在 `fault-lab/` 提供冻结场景、checkpoint/recovery、隔离数据集与 Ground Truth，在 `opspilot-evaluation` 提供单 Run 的八类确定性指标、Profile loader、结果渲染与写回。`docs/design/contracts/schemas/evaluation-profile.schema.json` 已允许在 `efficiency_limits` 中加入整数 Token 上限，但当前 `mvp-v1.yaml` 尚未给出该值；现有 `release-manifest.schema.json` 已表达 8 类质量门禁、三场景与 `runsPerScenario`，`scripts/phase0/generate_release_manifest.py` 则只是阶段 0 的固定示例生成器。

本阶段跨 Python Fault Lab、Java Evaluation、六进程 Agent/A2A、PostgreSQL/Artifact、真实模型/RAG、Observability、Compose 和发布证据。它不建立第二套调查或指标事实源：编排层只保存身份、快照、测试 case 和 Artifact 引用；单 Run 指标继续由独立 Evaluation 从原始事实计算，发布层只做确定性分组、阈值判定与证据汇总。

实现必须遵守 `specs/` 中九项能力合同。工作包依赖为：08-WP01 → 08-WP02；08-WP03～08-WP06 使用独立 Run 且可在 WP01 后按前置能力推进；08-WP07 消费 WP02 和所有调用/遥测账本；08-WP08 依赖 WP02、WP04～WP07 的稳定 harness 与观测；08-WP09 只能在 WP08 的精确检索结论后进入 ANN 分支，最后汇总全部 Release Manifest 证据。

## Goals / Non-Goals

**Goals:**

- 用一个冻结批次身份连接配置快照、15 次质量 Run、独立负向/恢复/性能 Run 与最终 Release Manifest。
- 让每个矩阵都由 Registry/合同展开并可做覆盖差异，避免手写清单悄悄漏掉新 endpoint、Tool 或 Source。
- 保持“原始事件 → 单 Run Evaluation → 场景聚合 → 发布门禁”的单向数据流，并让每一级结果可复算。
- 对 `PASSED`、`FAILED`、`BLOCKED`、`NOT_APPLICABLE` 使用明确状态语义；只有 ANN 的未触发分支可按合同 `NOT_APPLICABLE`。
- 让 08-WP01～08-WP09 的所有任务落到代码/配置、测试命令和 `outputs/phase8/` 证据三者之一或多者。
- Phase 只表示阶段目标；实现按可独立验证的小交付单元形成清晰 commit 和短生命周期 PR，尽早通过分层 CI 获取反馈，阶段末再执行完整发布门禁。

**Non-Goals:**

- 不修改三个冻结场景参数、Ground Truth、required Evidence、指标公式或阶段计划阈值。
- 不把 Fault Lab 扩展为通用 CI 平台，不新增动态插件框架或第二套通用工作流 DSL。
- 不为测试便利扩大 Agent、Tool、Evaluation 或发布编排器的生产权限。
- 不通过阶段 08 自动部署发布候选；持续交付仍终止于不可变 Artifact 与 Release Manifest。
- 不声明开发机测试代表生产容量、HA、RPO 或 RTO。

## Decisions

### 1. 08-WP01：在现有 Fault Lab 内增加发布门禁编排层

在 `fault-lab/src/fault_lab/release/` 增加窄职责模块：`snapshot` 规范化并冻结 commit/Profile/Prompt/模型/知识/机器摘要，`ledger` 以 append-only JSONL 加原子汇总索引记录 batch/Run 身份，`orchestrator` 只负责依赖顺序与外部进程调用，`evidence` 统一计算 Artifact SHA-256。CLI 在现有 `fault-lab` 下增加 `release baseline|run|verify` 子命令，不创建第二个 Python 工程。

首版先执行一个显式标记 `BASELINE_ONLY` 的真实 Run，统计正常调查 Token 分布，由人工/受审查步骤选择预算并发布 `docs/design/contracts/profiles/mvp-v2.yaml`，在 `efficiency_limits.total_tokens` 固化整数上限。基线产物永不进入质量分母；正式 batch 只接受包含该字段的新 Profile digest。快照同时包含六份 Agent profile/Prompt digest、Chat/Embedding/Rerank immutable revision、active knowledge collection revision、三个 Scenario version、Compose/image digest、CPU/内存/GPU/驱动和固定资源限制。

选择扩展 Fault Lab，是因为它已拥有场景、环境、恢复和隔离边界；另建 release-runner 会复制 Docker/数据集控制和 checkpoint 语义。拒绝直接以数据库作为唯一 ledger：环境尚未启动或数据库故障本身也是待测状态，先写本地 append-only ledger 与 Artifact，成功后再以只读核对器关联数据库事实更可靠。

正式 batch 获得唯一 `releaseBatchId`。每次启动前后比对 snapshot digest；一个 ledger 项只对应一个完整产品 Run，身份包含 `incidentId/runId/datasetRunId/a2aContextId/supervisorSessionId/attempt`，以及按专业角色保存的 `a2aTasks[]`。每个 `a2aTasks[]` 元素记录 `agentId/a2aTaskId/messageId/agentScopeSessionId`；正式质量 Run 必须恰好覆盖五个专业 Agent，`a2aContextId` 等于 `runId` 并只作为这些 A2A Task 的共享关联标识，Supervisor 属于同一 Run 但不伪装成第六个 A2A Task。阶段时间、状态、阻塞/失败码和 Artifact 引用保存在同一项中。写入采用临时文件、fsync/rename 和已有数据拒绝覆盖；恢复失败的 Run 保留原槽位与身份，不由编排器自动补齐。

### 2. 08-WP02：真实运行由 Fault Lab 编排，聚合由 Evaluation 扩展

Fault Lab 依照固定顺序对三个场景各创建五个新产品 Incident/Run。每个 Run 创建一个全新的 `contextId=runId`、一个 Supervisor AgentScope session，以及 evidence-collector、code-analysis、knowledge、diagnosis、remediation 五个专业 A2A Task；每个专业 Task 使用独立 `messageId` 和以 `agentId:a2aTaskId` 标识的 AgentScope session，禁止跨 Run 复用模型上下文。允许同场景复用已验证数据集，但必须显式保存相同 `datasetRunId`。每次 Run 完成后先锁定 RCA、Evaluation、usage、事件与 Artifact manifest，再开始下一次；进程退出码不替代结构化终态。

`opspilot-evaluation` 增加 release batch reader、场景聚合与 gate evaluator。它读取 15 份单 Run EvaluationResult 及冻结 Profile，先验证恰好 `3×5`、身份唯一、数据集有效、原始结果可复算，再按场景聚合和三场景等权 macro average 判阈值。报告保存每项分子、分母、去重集合、单 Run 值、场景值和总体值；INCONCLUSIVE 比例和“每场景至少 3/5 根因命中”作为显式门禁，不能从均值间接推断。

选择把聚合留在 Evaluation，是为了复用确定性指标模型并保持 Ground Truth 读取权不进入编排器。拒绝在 CI YAML 或 shell 中计算均值，因为那会形成不可单元测试的第二套公式。质量 batch 与测试 batch 在 ledger 中使用不同 `runPurpose`，聚合器只接受 `RELEASE_QUALITY`，任何未知或重复槽位 fail closed。

### 3. 08-WP03：用独立 fixture 构造三类业务空结果

`fault-lab` 增加不含 Ground Truth 泄露的 empty-outcome fixture 生成器：空 active collection 产生 `KB_EMPTY`；有 active Chunk 但黄金查询零候选产生真实 `NO_MATCH`；现场 Evidence 正常但案例集合为空产生 `INSUFFICIENT_HISTORY`。每个 fixture 固定 knowledge revision、查询、候选预期和可访问现场 Evidence，并使用独立 `EMPTY_OUTCOME` Run。

断言同时读取 Knowledge/A2A/Tool 调用轨迹、状态、RCA 和 Evaluation：前两类 Rerank 调用数必须为零，三类均不得产生技术 `ChainFailure`；结论等级、`rootCause`、`limitations/missingEvidence` 和 CitationValidity 必须相互一致。选择端到端 fixture 而非仅 mock Knowledge response，是为了证明真实 Embedding/数据库过滤与“空结果≠故障”边界；单元测试仍用于覆盖 mapper 分支，但不能作为发布证据。

Knowledge 的核心循环是通用产品能力，不因单 collection、多 collection、多租户或按目标系统选库而改变：差异只存在于 collection/revision 的解析策略，摄取、构建、覆盖校验、原子激活、Run 冻结、查询、回滚和引用安全清理必须保持同一事实源。阶段 08 不得在 Fault Lab 中复制这套生命周期，也不得通过测试身份直接更新 Knowledge 表。

若为 3.7 增加受控入口，seam 放在 `opspilot-core` 的 Knowledge application 层，以少量幂等命令表达“准备不可变 revision”“在 expected-active CAS 下激活并返回恢复凭据”“按恢复凭据还原”；`opspilot-adapters/knowledge-pgvector` 负责事务、覆盖门禁与引用完整性，`opspilot-server` 只提供内部 operator control-plane HTTP adapter。该入口不加入公开 Product OpenAPI，默认关闭，并通过独立网络、专用受限身份、测试 collection allowlist、TTL 与完整审计限制使用范围。

`fault-lab` 中的 Knowledge fixture manager 只是上述内部 control-plane 的测试 adapter：把版本化 fixture manifest 转成通用生命周期命令，等待 revision Ready，校验 digest/chunk/model identity，激活后运行 `EMPTY_OUTCOME`，最后使用恢复凭据还原。它不得拥有任意 SQL、生产 collection 写权限或独立的 revision 状态机。只有按此方式让生产运维入口与测试入口复用同一个深模块时，新增接口才同时提升测试便利性与架构紧凑度；若只能实现为表操作透传或 fixture CRUD，则不新增接口。

### 4. 08-WP04：由冻结 Registry 展开能力关键性矩阵

新增版本化 failure catalog，输入来自 Model Provider、五个专业 Agent Card/A2A endpoint、Tool Registry、Source Registry 和场景 criticality contract。生成器对每项能力展开 unavailable/timeout/auth/schema 四类 case，并要求每个 case 指向确定性 injector、恢复器和断言。对外部 HTTP 依赖优先使用 Toxiproxy/受控响应代理；对鉴权与 Schema 使用隔离凭证和恶意 fixture；不得通过直接改生产代码返回值模拟。

runner 在每个 case 前创建独立 `FAILURE_INJECTION` Run，之后执行恢复与健康校验。assertion engine 从 attempt/ChainFailure/missingEvidence/状态/调用路由读取事实，按 `MANDATORY`、`MANDATORY_WHEN_CANDIDATES_EXIST`、`CONDITIONAL`、`OPTIONAL_APPROVED` 判定。调用账本额外执行 provider/source/modelId/algorithm allowlist diff，检测隐藏 failover、vector-only、关键词、固定排序和跳过 Rerank。

选择“Registry 快照 + 明确例外”而非静态手写二维表，可以让新增 Tool/Source 自动使覆盖门禁失败；但 catalog 仍须提交版本控制，避免运行时动态发现改变已冻结测试范围。所有 case 原始报告位于 `outputs/phase8/08-WP04/cases/`，汇总矩阵不替代逐 case Artifact。

### 5. 08-WP05：恢复测试复用现有 checkpoint，以状态不变量检查器统一断言

建立 `recovery-concurrency` suite，覆盖四层状态的合法/拒绝转换、两个并发启动、CAS 旧版本、重复消息、租约过期/接管、取消与完成竞争、Artifact 哈希/权限失败、outbox 重放。并依次中断专业 Agent、客户端 SSE、PostgreSQL、Artifact、模型、Prometheus、Jaeger、Toxiproxy；恢复器必须先读取本地 checkpoint，再 Get/Subscribe 原 A2A Task，只有确认不存在时才按相同 messageId 幂等重建。

状态不变量检查器只读 PostgreSQL、A2A Task、outbox 与 Artifact 元数据，验证单活动 Run、attempt 单调、唯一终态、无重复副作用和 60 秒对账目标。使用单调时钟测恢复期限，UTC 时间只用于审计。SSE/metrics projector case 在权威事务提交后注入，断言只产生 projector outbox retry/告警，Incident/RCA 版本不回退。

选择一个跨层只读检查器而非在每个服务复制断言，是为了从同一时间点发现交叉状态矛盾；它不获得写权限，避免测试工具修复被测状态。失败后统一运行阶段 07 的恢复核验，残留 toxic、租约、长事务或停机服务会使 case 及后续环境门禁失败。

### 6. 08-WP06：攻击目录是数据，执行器按入口分组但共享审计合同

新增 `security-catalog.yaml`，每个 case 固定 `caseId/vector/entryPoint/actor/asset/payloadFixture/expectedBoundary/expectedCode/auditRule`。目录生成检查覆盖计划列出的全部向量，并与 Agent/Tool/Source/Provider Registry、A2A Card 和 Artifact route 快照做差异。执行实现仍放到所属模块的测试中：路径/Artifact 在 persistence/Artifact 集成测试，状态表与角色在 PostgreSQL Testcontainers，A2A 篡改在协议互操作测试，Prompt/Shell/沙箱在真实 Agent 安全 E2E，SSRF/allowlist 与 provenance 在 Adapter 测试。

安全汇总器收集各套件标准化 case report，不把“HTTP 4xx”单独视为成功；它还验证拒绝层、无副作用、审计字段和响应脱敏。UnsafeAction 同时保存 attempted 与 executed；任何执行硬禁令立刻把 batch 标记 `FAILED`，但仍尽最大可能执行环境恢复与剩余只读扫描。

泄密扫描对日志、Trace、SSE、Actuator、测试报告、RCA 和所有 Artifact 使用每批唯一 canary、配置 Secret 的单向摘要匹配及禁止字段/大文本策略。扫描器输出只含规则 ID、载体 URI、脱敏位置和 digest，不复制命中值。无法访问任一必扫载体时为 `BLOCKED/FAILED`，不是零发现。

选择数据驱动目录，是为了能证明建设范围第 5 项没有遗漏；拒绝把通用 SAST/Secret scanner 当作全部安全验收，因为它们无法验证运行身份、跨 Run 授权、A2A 篡改和实际副作用。

### 7. 08-WP07：原始事件对账与效率判定分离

可观测性断言从产品错误/SSE 选择样本，沿 `requestId/traceId/runId/stepId/a2aTaskId/invocationId` 关联日志、Trace、状态事件、Provider/Tool attempt 与错误 Artifact；指标检查从 Meter registry 导出 descriptor，拒绝 run/incident/URL/Prompt/error 原文等高基数或敏感标签，并核对 Dashboard/告警所需序列存在。

ledger reconciler 以 usage、Provider response metadata、A2A/Tool 事件和 wall-clock 阶段为原始输入，复算 input/output Token、估算方法/单价版本、cost、调用、重试、失败和等待。差异报告采用“期望/实际/来源/解释码”；只有预先登记的估算舍入或 Provider 未提供 usage 才可产生版本化解释，不能用自由文本忽略差异。

`opspilot-evaluation` 的 Efficiency 判定补充 `total_tokens`，对 15 个 Run 逐一输出 Supervisor rounds、每个专业 Agent rounds、Tool calls、专业 A2A attempts、去除用户等待后的 wall-clock 和 Token。聚合报告只做列表/分布，不创造综合分数；任何一个 Run 任一维超限即失败。

### 8. 08-WP08：一个性能 harness、六类测量阶段和前后资源快照

在 `scripts/performance/` 建立可复用 harness 配置与 runner，复用已有 `scripts/retrieval/benchmark_*` 的真实 Infinity 探针与最近秩分位数实现，但把数据准备、预热、测量、资源采集和报告格式统一。数据装载必须生成 50,000 个 active Chunk，并通过真实 `searchable=true + model revision + collection` 过滤核对数量与黄金候选。

性能阶段固定为：非流式读 API、SSE 已提交事件重放首事件、精确 candidate recall、完整 Embedding+召回+Rerank、单 Incident E2E、进程重启恢复。每阶段有独立预热与测量窗口、固定并发模型、最小有效样本和目标 20 QPS 校验；没有达到 offered/achieved load 的结果无效。报告保存逐样本开始/结束、状态、阶段延迟，以及 p50/p95/p99、吞吐、错误率。

资源 collector 在 batch 前、每 Run、恢复后和最终稳定等待窗口采集宿主/容器 CPU、内存、GPU、OOM、JVM、线程、HTTP/数据库连接和权威状态计数。连接泄漏/资源增长容差必须在配置中预先冻结；15 Run 后检查引用的是同一个正式 batch，而非另做轻量循环。

选择扩展现有脚本而非引入 JMeter/Gatling，是因为首版负载面有限且已有 Python 探针能保存完整原始样本；若自制 harness 无法稳定维持 20 QPS，门禁应 `BLOCKED`，后续再通过独立变更引入专用工具，不在本阶段临时扩展依赖。

### 9. 08-WP09：ANN 是条件子图，发布证据是最终唯一出口

ANN decision 先读取 WP08 有效精确检索报告及 digest。p95 `<300ms` 时生成 `NOT_APPLICABLE` ADR，验证没有阶段 08 创建的 ANN 索引；p95 `≥300ms` 时才允许在同 revision/filter/query/load 下建立 HNSW 和 IVFFlat。对照 runner 同时采集 Recall@K、p95、索引大小/构建时间、WAL bytes、写吞吐、内存，并按“创建 → 校验 → 切换 → 黄金查询 → 回滚 → 删除”执行生命周期。选择必须先满足候选完整性/Recall，再比较性能与资源。

可移植性 suite 通过 Registry 配置禁用 Java Code Analyzer/Maven Sandbox Adapter，检查 RCA 不出现代码级事实/位置/补丁；再以测试作用域注册 Loki/Tempo Adapter，执行既有 Observability Adapter contract，并对 core API、四层状态矩阵、Evidence/RCA schema 与 migration、A2A skill major version 做快照 diff。

各套件产出统一 evidence envelope：`schemaVersion/suiteId/suiteVersion/commit/workflowRunId/runIdentity/startedAt/endedAt/status/cases/artifacts`。`artifacts` 只保存 URI、大小和 SHA-256。最终扩展 `scripts/phase0/generate_release_manifest.py` 为通用 `scripts/release/generate_release_manifest.py`，从 evidence index 生成现有 frozen `release-manifest.schema.json` 格式；阶段 0 脚本保留，避免无关回归。生成器严格区分：断言失败为 `FAILED`，前置缺失为 `BLOCKED`，仅已定义条件分支可 `NOT_APPLICABLE`，全部必需门禁通过才可 `READY_FOR_MANUAL_DEPLOYMENT`。

选择复用现有 Release Manifest Schema，是因为它已表达持续交付边界和 8 类权威质量门禁；阶段 08 的细粒度报告通过 `TEST_EVIDENCE` bundle 与各 gate 的 evidenceArtifact 索引承载，不为本次实现随意扩展发布公共合同。

### 10. 测试层级、目录与证据布局

所有快速 Schema/单元测试先运行，再执行 PostgreSQL/Testcontainers、Compose/Adapter、真实模型/A2A、15 Run、负向安全、恢复和性能。测试命令由各工作包固定，至少包括 `python -m pytest fault-lab/tests`、`./mvnw verify`、阶段 08 新增的 Python suite、Compose profile 运行和 `openspec validate --strict`；真实 E2E 报告必须记录实际命令、环境与退出码，单元测试报告不得占用 E2E suiteId。

证据布局固定为：

```text
outputs/phase8/
  08-WP01/{baseline,snapshot,run-ledger}/
  08-WP02/{runs,aggregate}/
  08-WP03/{cases,empty-outcome-matrix.json}/
  08-WP04/{cases,criticality-matrix.json}/
  08-WP05/{cases,recovery-concurrency-matrix.json}/
  08-WP06/{cases,secret-scan,security-gate.json}/
  08-WP07/{telemetry,ledger,efficiency}/
  08-WP08/{raw,performance-gate.json,resource-stability.json}/
  08-WP09/{ann,portability,evidence-index.json,release-manifest.json}/
```

原始样本可压缩为 Artifact，但索引和 digest 必须留在仓库约定的证据目录；敏感原始响应不得因为“测试证据”例外而写入 Artifact。

## Risks / Trade-offs

- [15 次真实模型调查加性能与负向矩阵耗时和成本高] → 先用单元/合同测试淘汰确定性错误，再按工作包门禁顺序运行昂贵套件；正式 batch 不并发到改变冻结负载或 Provider 限额。
- [基线 Token 样本可能偏低而导致后续合理 Run 超限] → 基线报告完整保留分布和场景维度，由受审查的新 Profile version 固化预算；发布运行后不自动调整。
- [复用同一 datasetRunId 可能让运行不独立] → 只允许复用不可变 input，A2A/Task/session/模型上下文必须全新；ledger 同时证明复用范围与隔离身份。
- [故障/安全 case 可能破坏共享 Compose 环境] → 每 case 前后执行健康与残留校验，使用受限 injector 和独立身份；恢复失败终止后续可变 case 并保留 `BLOCKED/FAILED`。
- [遥测最终一致性造成扫描或关联假阴性] → 使用冻结的最大采集等待窗口并记录 event time/ingest time；窗口耗尽仍缺失即失败，不无限等待。
- [自制负载生成器产生 coordinated omission] → 以固定到达率记录 planned/actual start 和排队延迟，报告 offered/achieved QPS；达不到目标时结果无效。
- [ANN 对照改变数据库写放大或留下索引] → 使用同 revision 的测试 collection/受控 migration，生命周期必须包含回滚与删除，WAL/写吞吐作为选择条件而非事后备注。
- [Release Manifest 的 8 类 gate 粒度小于阶段 08 矩阵] → evidence index 保存细粒度状态，Manifest 的权威 gate 指向对应汇总 Artifact；任何子门禁失败向上折叠为 FAILED，不能丢失细节。
- [安全扫描自身泄漏 Secret] → 只比较 canary/摘要并输出规则与脱敏位置，扫描器输入使用只读临时身份，报告不保存命中原文。

## Migration Plan

1. 合入 Profile v2、发布快照/ledger 与 evidence envelope，不启动正式质量 Run；用 fixture 验证漂移、覆盖差异和状态折叠。
2. 接入单 Run 产品/Evaluation 编排与 15 Run 聚合，先用历史/合成 Evaluation fixture 验证公式，再完成真实基线并冻结 Token budget。
3. 依次启用空结果、技术失败、恢复并发和安全目录；每个 suite 通过恢复与泄密扫描后才允许进入下一个破坏性矩阵。
4. 接入遥测/账本复算与逐维效率，在一轮非正式真实 Run 上验证关联和容差后启动不可变 15 Run 正式 batch。
5. 完成固定数据装载、负载/资源 harness 和性能报告；根据有效精确检索结果进入 ANN 的“不适用”或对照分支。
6. 运行禁用代码能力和 Loki/Tempo 测试 Adapter 的可移植性 suite，汇总 evidence index，并生成/校验 Release Manifest。
7. 回滚时停止 release orchestrator，恢复所有 fault injector、租约和服务，保留 append-only ledger、原始 Artifact 和失败报告；删除仅由 ANN 测试创建的索引，代码/Profile 回退不修改既有 batch 证据。
