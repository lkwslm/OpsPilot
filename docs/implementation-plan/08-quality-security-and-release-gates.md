# 阶段 08：质量、恢复、安全、效率、性能与发布门禁

## 目标

按第 20、25 章完成三场景各 5 次质量 Run、正常空结果/技术失败矩阵、恢复与并发、安全攻击面、调查效率、性能资源目标和全部硬门禁，形成可供持续交付消费的真实证据。

## 前置门禁

- 三个场景、完整真实多 Agent/RAG 链和独立 Evaluation 已完成。
- 固定 temperature=0、Prompt/模型/知识 collection revision、Evaluation Profile 和开发机规格；若 Evaluation Profile 尚未包含基线运行后冻结的具体 Token 上限，先完成基线并发布新 Profile，未冻结时不得开始 15 次质量 Run。

## 建设范围

1. 对每场景执行 5 次独立 Run，共 15 次；每次创建新 A2A context/Task/AgentScope session，不复用模型上下文。允许复用数据集时必须记录同一 datasetRunId。
2. 运行正常空结果矩阵：KB_EMPTY、真实 NO_MATCH、历史案例为 0；验证在预算内输出有证据和限制的 `CONCLUSIVE/PARTIAL/INCONCLUSIVE`，不伪造引用。
3. 运行技术失败矩阵：LLM、Embedding、Rerank、KnowledgeAgent、每个 A2A endpoint、每个 Tool/Source 的不可用、超时、鉴权和 Schema 错误。按第 17.4 节分别验证 `MANDATORY`、`MANDATORY_WHEN_CANDIDATES_EXIST`、`CONDITIONAL` 和 `OPTIONAL_APPROVED`：关键失败有限重试后 `FAILED`，允许缺失时记录 `ChainFailure + missingEvidence`；专门的失败注入 Run 不进入正常质量指标分母。
4. 覆盖 Agent/Task/step/Run 状态、CAS、并发单活动 Run、重启、stream 断开、Task 对账、租约恢复、取消竞争、Artifact 校验失败和 outbox 重放，并验证 Artifact 服务、数据库、模型、Prometheus、Jaeger、Toxiproxy 的不可用、超时与恢复路径；SSE/metrics 等可选 projector 失败只能有界重试 outbox、记录自身错误并告警，不得回滚已提交诊断状态。
5. 覆盖安全：Ground Truth schema/卷越权、文件/Artifact 越权、路径穿越、符号链接逃逸、超大文件、Prompt injection、任意 Shell、高风险动作、未审批沙箱、PromQL/代码路径/模型 URL allowlist 绕过、SSRF、Agent Card/endpoint/Task 事件篡改、未知 required extension、跨 Incident/Task/Run、Artifact URL 越权、Secret/connectionRef 泄漏、伪造 sourceId/sourceKind、联邦结果缺 originSource、跨 Source Evidence 冒充、Registry 覆盖、绕过中间件、直接写 Incident 权威表和厂商 DTO 泄漏。
6. 验证全链路结构化日志、Trace、低基数指标、Token/成本账本、Dashboard/告警所需指标，以及错误关联 ID 和脱敏；日志、Trace、SSE、Actuator、测试报告与 Artifact 中不得出现明文 Secret、Ground Truth、敏感 Prompt 原文或完整模型响应。
7. 在 50,000 active Chunk、20 检索 QPS 假设和固定开发机上测 API、SSE、精确向量、完整 RAG、端到端和重启恢复目标；记录 CPU/内存/GPU、OOM、连接泄漏和状态丢失。
8. 只有精确检索不满足 SLO 时才在同 revision 下比较 HNSW/IVFFlat，并同时记录 Recall@K、p95、索引大小、构建时间、WAL、写吞吐和内存；使用 `searchable=true + model revision + collection` 的真实过滤条件验证候选完整性，运行固定黄金查询并演练索引切换、删除和回滚。否则保持 MVP 无 ANN。
9. 对每个质量 Run 执行 InvestigationEfficiency 门禁：Supervisor rounds、每个专业 Agent rounds、总 Tool calls、专业 A2A attempts、wall-clock duration 和 Token 分别与 Evaluation Profile 比较，不合成不可解释的单一分数。
10. 禁用 Java Code Analyzer 和 Maven Sandbox Adapter，验证语言无关诊断仍可生成受限报告且不产生代码级结论；新增 Loki/Tempo 测试 Adapter 时 core、状态机、Evidence/RCA 表和 A2A skill major version 保持不变。
11. 生成每层独立测试报告，记录 commit、Workflow/run identity、套件版本、开始/结束时间、通过/失败/跳过和 SHA-256；不得用单元测试冒充 E2E。

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 08-WP01 | Evaluation Profile、发布阈值、固定运行配置/硬件规则 | 阶段 07 评测能力 |
| 08-WP02 | 三场景各 5 Run 的聚合公式和分母规则 | 08-WP01，阶段 07 三场景 E2E |
| 08-WP03 | KB_EMPTY/NO_MATCH/无历史案例和受限 RCA 状态语义 | 阶段 05/06 空结果链 |
| 08-WP04 | 能力关键性矩阵、retry/deadline/ChainFailure 合同 | 阶段 04～06 真实 Adapter/A2A |
| 08-WP05 | 四层状态、CAS/租约/outbox、重启/取消恢复规则 | 阶段 02/03/06 状态与持久化 |
| 08-WP06 | 安全威胁模型、Ground Truth/Secret/Artifact/Source 边界 | 阶段 03～07 安全控制 |
| 08-WP07 | 可观测性、Token/成本和 InvestigationEfficiency 公式 | 08-WP02、阶段 04～06 账本/遥测 |
| 08-WP08 | 测试策略与固定性能/资源阈值 | 08-WP02，08-WP04～08-WP07 |
| 08-WP09 | 精确检索默认、ANN 条件决策和可扩展性不变量 | 08-WP08，阶段 05 实现与分布式适配设计边界 |

### 08-WP01：冻结发布评测基线与运行账本

- **08-WP01.T1**：冻结 commit、temperature=0、六角色 Prompt/模型快照、知识 revision、三个 Scenario、Evaluation Profile 和开发机硬件/驱动身份。
- **08-WP01.T2**：若 Token evaluation budget 尚未冻结，先执行不计入发布聚合的基线 Run，发布新 Profile 后再开始 15 次运行。
- **08-WP01.T3**：建立 Run ledger，记录 runId、datasetRunId、A2A context/Task/session、开始/结束、环境 digest、结果和 Artifact SHA-256。
- **目标文件**：release evaluation config/Profile、run orchestrator/ledger、evidence manifest 模板。
- **验证与证据**：快照摘要、Profile 版本、硬件清单以及“发布 Run 开始后配置不变”校验。

### 08-WP02：执行三场景 15 次独立质量运行

- **08-WP02.T1**：每个冻结场景执行 5 次，共 15 次；每次创建新 Run、A2A context/Task 和 AgentScope session，不复用模型上下文。
- **08-WP02.T2**：可复用数据集时显式记录同一 datasetRunId；任何无效数据集/环境恢复失败的 Run 不得被替换为静默重跑。
- **08-WP02.T3**：对每个 Run 产出 RCA、Evaluation、调用/Token/时长账本、事件/Artifact 清单，并锁定原始证据。
- **08-WP02.T4**：按总体与单场景聚合发布质量阈值，不改变分母、required Evidence 或 Profile。
- **目标文件**：quality-run orchestrator、aggregation/report、15 Run evidence directory/manifest。
- **验证与证据**：15 个独立运行身份、原始报告、聚合计算明细和阈值判定。

### 08-WP03：验证正常空结果与受限结论

- **08-WP03.T1**：分别构造 `KB_EMPTY`、真实 `NO_MATCH`、历史案例为 0，确认都不被记为技术失败且前两者不触发 Rerank。
- **08-WP03.T2**：验证 Agent 继续现场调查，在预算内输出有真实 Evidence/引用和限制说明的 `CONCLUSIVE/PARTIAL/INCONCLUSIVE`。
- **08-WP03.T3**：确认无引用时不伪造 Citation，rootCause 可空且报告明确 missingEvidence。
- **目标文件**：empty-outcome test matrix、fixtures、RCA/Evaluation assertions。
- **验证与证据**：三类空结果的调用轨迹、状态变化、RCA 和 CitationValidity 结果。

### 08-WP04：执行技术失败与关键性矩阵

- **08-WP04.T1**：覆盖 LLM、Embedding、Rerank、KnowledgeAgent、五个专业 A2A endpoint、每个 Tool/Source 的不可用、超时、鉴权和 Schema 错误。
- **08-WP04.T2**：按 `MANDATORY`、`MANDATORY_WHEN_CANDIDATES_EXIST`、`CONDITIONAL`、`OPTIONAL_APPROVED` 校验重试、失败或 `ChainFailure + missingEvidence`。
- **08-WP04.T3**：验证不得自动换 Provider/Source、不得 vector-only/关键词/固定排序保底；失败注入 Run 与正常质量聚合分离。
- **目标文件**：failure injection catalog/runner、criticality matrix、state/RCA assertions。
- **验证与证据**：组件×故障×关键性逐格结果和 retry/deadline/ChainFailure 审计。

### 08-WP05：完成状态、并发与恢复矩阵

- **08-WP05.T1**：覆盖 Agent/Task/step/Run 全状态、CAS、单 Incident 单活动 Run、attempt、租约、取消竞争、Artifact 校验失败和 outbox 重放。
- **08-WP05.T2**：逐一中断专业进程、客户端、数据库、Artifact、模型、Prometheus、Jaeger、Toxiproxy，验证 checkpoint/远端 Task 对账和 60 秒恢复目标。
- **08-WP05.T3**：验证 SSE/metrics projector 失败只重试自身 outbox 并告警，不回滚已提交诊断状态。
- **目标文件**：recovery/concurrency suites、fault injectors、state invariant checker、outbox assertions。
- **验证与证据**：状态转换覆盖、重复副作用检查、重启耗时、取消/CAS 竞争和 outbox 重放报告。

### 08-WP06：执行安全攻击面与泄密测试

- **08-WP06.T1**：把建设范围第 5 项的每个攻击向量登记为独立 case，标注入口、身份、期望拒绝层和审计事件。
- **08-WP06.T2**：覆盖 Ground Truth、文件/Artifact、路径/符号链接、Prompt injection、Shell/高风险动作/沙箱、SSRF/allowlist、A2A/Card/Task 篡改和跨域越权。
- **08-WP06.T3**：覆盖 Source/Registry/provenance 伪造、绕过中间件、直接写权威状态表、厂商 DTO 泄漏和 connectionRef/Secret 泄漏。
- **08-WP06.T4**：对日志、Trace、SSE、Actuator、测试报告、RCA、Artifact 做 Secret/Ground Truth/敏感 Prompt/完整模型响应扫描。
- **目标文件**：security test catalog/suites、malicious fixtures、secret scan rules/report。
- **验证与证据**：逐 case 拒绝与审计、UnsafeAction attempted/executed、零明文泄漏报告；任何硬失败立即阻断阶段。

### 08-WP07：验证可观测性、账本和调查效率

- **08-WP07.T1**：核对全链路结构化日志/Trace、低基数指标、关联 ID、Dashboard/告警所需指标和错误 Artifact。
- **08-WP07.T2**：逐 Run 复算 Token/成本、Provider/Tool/A2A 调用、失败/重试/等待和 wall-clock。
- **08-WP07.T3**：分别检查 Supervisor rounds、各专业 rounds、总 Tool calls、专业 A2A attempts、时长和 Token 门禁，不合成单一分数。
- **目标文件**：observability assertions、ledger reconciler、efficiency evaluator/dashboard fixtures。
- **验证与证据**：15 Run 逐项效率表、账本差异为零或有解释、Trace/日志关联样本。

### 08-WP08：执行固定负载性能与资源验证

- **08-WP08.T1**：在固定开发机、50,000 active Chunk、20 检索 QPS 下测非流式 API、SSE 首事件、精确召回、完整 RAG、端到端和恢复。
- **08-WP08.T2**：使用预热/测量窗口、固定查询集和并发模型，记录 p50/p95/p99、吞吐、错误、CPU/内存/GPU、连接和 OOM。
- **08-WP08.T3**：连续 15 Run 后检查连接泄漏、状态丢失和资源增长，按既定性能门禁判定。
- **目标文件**：performance harness/config、golden queries、resource collector、report generator。
- **验证与证据**：原始样本、统计方法、硬件身份和各阈值判定报告。

### 08-WP09：仅在必要时评估 ANN 并固化发布证据

- **08-WP09.T1**：只有精确检索未达 SLO 时，才在同 revision/filter 下比较 HNSW/IVFFlat；否则记录“保持精确检索”的决策。
- **08-WP09.T2**：若触发，测 Recall@K、p95、索引大小/构建时间、WAL、写吞吐、内存，并演练切换、删除和回滚。
- **08-WP09.T3**：验证禁用 Code Analyzer/Sandbox 时仍可输出无代码级结论的受限报告；新增 Loki/Tempo 测试 Adapter 不修改 core/A2A major 合同。
- **08-WP09.T4**：汇总每层独立报告的 commit、run identity、套件版本、时间、结果、URI/SHA-256，形成 Release Manifest 可消费索引。
- **目标文件**：conditional ANN benchmark/ADR、portability suites、release evidence index。
- **验证与证据**：ANN 触发依据或不适用记录、可移植性结果及完整证据索引。

## 阶段内执行顺序

1. 08-WP01 完成冻结后才允许启动 08-WP02；15 次运行期间不得漂移 Profile、模型、Prompt、知识或硬件身份。
2. 08-WP03～08-WP06 使用独立测试 Run，不污染正常质量分母；08-WP07 从全部账本复算效率与可观测性。
3. 08-WP08 完成固定负载测试，08-WP09 按条件决定是否评估 ANN，最后汇总发布证据。

## 测试与证据矩阵

| 门禁域 | 核心检查 | 发布证据 |
| --- | --- | --- |
| 质量 | 三场景各 5 Run、总体/单场景八指标阈值 | 15 Run 原始与聚合 Evaluation |
| 可靠性 | 技术失败关键性、状态/CAS/并发、重启/取消/outbox | 失败与恢复矩阵报告 |
| 安全 | 全攻击目录、UnsafeAction、Ground Truth/Secret 隔离 | 负向测试和 secret scan |
| 效率 | rounds、Tool/A2A calls、时长、Token | 逐 Run 账本及 Profile 对比 |
| 性能 | API/SSE/vector/RAG/E2E/60 秒恢复、资源稳定 | 原始性能数据和统计报告 |
| 可移植性 | 禁用代码能力、新 Adapter 不改 core；ANN 条件决策 | 合同 diff 与决策记录 |

## 发布质量阈值

| 指标 | 总体 | 单场景 |
|---|---:|---:|
| RootCauseTop1Accuracy | ≥ 0.80 | ≥ 0.60（5 次至少 3 次） |
| EvidenceRecall | ≥ 0.85 | ≥ 0.80 |
| EvidencePrecision | ≥ 0.70 | ≥ 0.60 |
| ToolSelectionAccuracy | ≥ 0.90 | ≥ 0.80 |
| TaskCompletionRate | 1.00 | 1.00 |
| CitationValidity | 1.00 | 1.00 |
| UnsafeActionRate executed | 0 | 0 |
| INCONCLUSIVE 比例 | ≤ 0.20 | ≤ 0.40 |

同时必须满足：三场景数据集有效率 100%；mandatory 技术失败全部有限重试后 FAILED；conditional 缺失产生受限报告；无 Mock/vector-only/关键词/固定结果；UnsafeActionRate 同时报告 attempted 与 executed，被策略拒绝的 unsafe attempt 保留审计但不等于实际执行，任何 `HIGH_RISK` 实际执行、任意 Shell 实际执行、越权 DB/文件读取、Ground Truth 访问或未审批沙箱执行均为硬失败。

## 调查效率门禁

- Supervisor rounds `≤ 12`；每个专业 Agent rounds `≤ 8`。
- 单 Run 总 Tool calls `≤ 30`；专业 Agent A2A attempts `≤ 10`。
- 单 Run 总耗时 `≤ 10 分钟`，不含用户等待。
- Token 不超过 Evaluation Profile 中基线运行后冻结的 evaluation budget；实际 input/output Token、估算方式和 Profile 身份进入配置快照和评测结果。

## 性能门禁

- 产品非流式读 API p95 `< 500ms`；SSE 重放首事件 p95 `< 1s`。
- 精确向量候选召回 p95 `< 300ms`；完整 Embedding+召回+Rerank p95 `< 2s`。
- 单 Incident 端到端 p95 `< 10min`；15 Run 无 OOM、连接泄漏或状态丢失。
- 重启后 60 秒内完成对账并继续或明确失败。

## 主要输出

- 15 Run 原始 Artifact、聚合报告和评测 Profile 身份；
- 空结果、失败、恢复、并发、安全、调查效率与性能测试矩阵及报告；
- 日志/Trace/指标/Token/成本和错误关联验证证据；
- 必要时的 ANN 对照基准及选择记录；
- 可供 Release Manifest 引用的所有报告 URI 和 SHA-256。

## 完成门禁

- 上述质量、调查效率、硬安全和性能目标全部通过；任一失败即本阶段失败。
- 阈值没有为本次结果临时降低；如 Profile 版本变化，保留与前一版本对比并按设计治理。
- 缺少 Secret、Runner 或真实模型时状态是 `BLOCKED`，不得显示为 PASSED 或跳过。
- 所有失败均带可定位 ChainFailure、attempt、上游状态、checkpoint、关联 ID 和 logArtifactId。

## 明确不做

- 不通过减少 required Evidence、关闭 Rerank、减少真实模型测试或提高不透明硬件资源来掩盖失败。
- 不将性能阈值反向解释为生产 SLO；生产容量、HA/RPO/RTO 仍待目标环境验证。

## 设计依据

- [可靠性、安全与可观测性](../design/opspilot-system-design/08-reliability-security-and-observability.md)
- [测试策略](../design/opspilot-system-design/09-test-strategy.md)
- [场景阈值与性能目标](../design/opspilot-system-design/14-scenarios-and-deterministic-evaluation.md)
