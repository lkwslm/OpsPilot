> **执行约束：** 不以整个 Phase 作为一次提交边界；完成可独立验证的小交付单元后，应形成清晰 commit 和短生命周期 PR，并尽早使用分层 CI 验证，完整 fault-lab 批次保留在阶段或发布门禁执行。

## 1. 08-WP01：冻结发布评测基线与运行账本

- [x] 1.1 `08-WP01.T1` 在 `fault-lab/src/fault_lab/release/` 建立发布门禁包及 `baseline/run/verify` CLI 骨架，定义 `releaseBatchId`、`runPurpose`、`PASSED|FAILED|BLOCKED|NOT_APPLICABLE` 状态和稳定错误码，并用 CLI help/参数负向测试验证入口。
- [x] 1.2 `08-WP01.T1` 实现 snapshot collector，规范化并记录 commit、temperature=0、六角色 Agent Profile/Prompt digest、Chat/Embedding/Rerank immutable revision、active knowledge collection revision、三个 Scenario version、Evaluation Profile digest、Compose/image digest 和 CPU/内存/GPU/驱动身份。
- [x] 1.3 `08-WP01.T1` 为 snapshot collector 增加 Secret/connectionRef 脱敏、缺字段、未知模型 revision、非零 temperature 和硬件采集失败测试，证明任一冻结项不完整时 fail closed。
- [x] 1.4 `08-WP01.T2` 扩展基线流程，执行一个标记为 `BASELINE_ONLY` 且不进入发布聚合的真实 Run，产出逐角色/总 Token、时长和环境报告到 `outputs/phase8/08-WP01/baseline/`。
- [x] 1.5 `08-WP01.T2` 根据受审查基线发布 `docs/design/contracts/profiles/mvp-v2.yaml`，在 `efficiency_limits.total_tokens` 固化整数预算，保留 `mvp-v1.yaml`，并通过 Evaluation Profile Schema、loader 与版本不可覆盖测试。
- [x] 1.6 `08-WP01.T3` 实现 append-only Run ledger、原子索引和 Artifact SHA-256 manifest；一条 ledger 项记录一个完整 Run 的 `incidentId/runId/datasetRunId/a2aContextId/supervisorSessionId/attempt`，并在 `a2aTasks[]` 中按专业角色记录各自 `a2aTaskId/messageId/agentScopeSessionId`，同时保存时间、环境 digest、终态及失败/阻塞原因。
- [x] 1.7 `08-WP01.T3` 增加缺失/重复专业角色与 Task/message/session、Supervisor 被误记为第六个 Task、重复身份、覆盖既有条目、同一 Run 冲突 digest、可复用 dataset 与独立上下文、环境恢复失败和进程中断写入测试，证明 ledger 不会静默替换、选择代表性身份或产生半条目。
- [x] 1.8 `08-WP01.T1-T3` 运行 Fault Lab release 包单元/合同测试与一次基线 smoke，输出 `outputs/phase8/08-WP01/snapshot/`、`run-ledger/` 和 `release-baseline-gate.json`；配置、Profile 或前置真实依赖缺失时门禁必须为 `BLOCKED`。

## 2. 08-WP02：执行三场景十五次独立质量运行

- [x] 2.1 `08-WP02.T1` 实现 `RELEASE_QUALITY` 批次计划器，固定三个 `1.0.0` 场景各 5 个槽位并拒绝缺失、重复、额外场景、动态 run count 或非 WP01 snapshot digest。
- [x] 2.2 `08-WP02.T1` 接入产品 API 及 PostgreSQL A2A/AgentScope 只读查询，为每个槽位创建唯一 Incident/Run，校验 `a2aContextId=runId`、唯一 Supervisor session，以及 evidence-collector、code-analysis、knowledge、diagnosis、remediation 五组各自唯一的 `a2aTaskId/messageId/agentScopeSessionId`，并将完整身份集合写入单条 Run ledger，禁止复用模型上下文。
- [x] 2.3 `08-WP02.T2` 实现 dataset 选择与恢复前置校验，允许显式复用同一有效 `datasetRunId`，但对无效数据集、恢复失败或环境漂移保留原槽位失败并禁止自动补跑。
- [x] 2.4 `08-WP02.T3` 实现单 Run evidence sealer，收集并锁定 RCA JSON/Markdown、Evaluation、Provider/Tool/A2A/usage、Token/成本/时长、状态事件、Citation 和 Artifact URI/大小/SHA-256。
- [x] 2.5 `08-WP02.T3` 扩展 `opspilot-evaluation` release batch reader，校验 15 个 Run 的 purpose、身份、数据集有效性、Profile/snapshot 一致性、原始证据完整性和单 Run Evaluation 可复算性。
- [x] 2.6 `08-WP02.T4` 实现场景 5 Run 聚合、三个场景等权 macro average、INCONCLUSIVE 比例和单场景 Root Cause 至少 3/5 命中，并在报告中保存每项分子、分母、单 Run/场景/总体值。
- [x] 2.7 `08-WP02.T4` 实现全部总体/单场景质量阈值和 hard gate evaluator，覆盖边界等于阈值、一个场景失败但总体通过、Citation `notApplicable`、Unsafe attempted/executed 与数据集有效率 100% 的测试。
- [x] 2.8 `08-WP02.T4` 实现聚合输入隔离，拒绝 `BASELINE_ONLY`、`EMPTY_OUTCOME`、`FAILURE_INJECTION`、`RECOVERY`、`SECURITY`、`PERFORMANCE` Run、Mock/隐藏 fallback/跳过 Rerank 和 Profile 漂移进入分母。
- [x] 2.9 `08-WP02.T1-T4` 运行 release orchestration 与 aggregation 的 Java/Python fixture 测试，输出无真实模型的公式/分母/身份负向报告到 `outputs/phase8/08-WP02/aggregate/`。
- [x] 2.10 `08-WP02.T1-T4` 在冻结真实环境执行三场景各 5 次正式质量 Run，保存 15 份原始证据和 `quality-release-gate.json`；任一 Run 失败、缺证据或配置漂移时如实 `FAILED/BLOCKED`，不得补跑替换。

## 3. 08-WP03：验证正常空结果与受限结论

- [x] 3.1 `08-WP03.T1` 建立版本化 empty-outcome fixture/manifest，固定 knowledge revision、查询、现场 Evidence 与预期调用，分别登记 `KB_EMPTY`、真实 `NO_MATCH` 和 `INSUFFICIENT_HISTORY`。
- [x] 3.2 `08-WP03.T1` 实现空 active Chunk 的 `KB_EMPTY` 真实集成 case，断言结构化正常结果、Rerank 调用为零、无技术 `ChainFailure` 且继续现场调查。
- [x] 3.3 `08-WP03.T1` 实现有 active Chunk 但真实过滤/检索零候选的 `NO_MATCH` case，断言无 Rerank、无关键词/固定候选 fallback 和无伪造 Citation。
- [x] 3.4 `08-WP03.T1` 实现现场 Evidence 可用但历史案例为 0 的 case，断言零历史为正常业务结果并保留 Knowledge 调用轨迹。
- [x] 3.5 `08-WP03.T2` 增加预算与状态断言，验证三类 Run 在冻结 rounds、Tool/A2A、时长和 Token 上限内形成 `CONCLUSIVE|PARTIAL|INCONCLUSIVE`，且限制与 `missingEvidence` 一致。
- [x] 3.6 `08-WP03.T3` 增加 RCA/Citation 负向测试，覆盖无证据时 `INCONCLUSIVE/rootCause=null/notApplicable`、事实性结论无引用、跨 Run 引用和伪造 evidenceCode。
- [ ] 3.7 `08-WP03.T1-T3` 通过复用通用 Knowledge revision 生命周期的内部 control-plane adapter（不得直接写表或建立 fixture 专用状态机）准备并切换三个测试 revision，运行三类独立真实 E2E，输出调用轨迹、状态、RCA、Evaluation 与 `outputs/phase8/08-WP03/empty-outcome-matrix.json`，使用恢复凭据还原原 revision，并证明这些 Run 未进入 15 Run 分母。

## 4. 08-WP04：执行技术失败与关键性矩阵

- [ ] 4.1 `08-WP04.T1` 定义并严格校验版本化 failure catalog，字段包含 caseId、组件/endpoint、`sourceId/sourceKind/adapterId`、故障类型、injector、恢复器、criticality、期望终态和断言。
- [ ] 4.2 `08-WP04.T1` 从冻结 Model Provider、五个专业 Agent Card/A2A endpoint、Tool Registry、Source Registry 生成覆盖快照，对每项展开 unavailable/timeout/auth/schema case，并用 registry diff 阻断遗漏或未登记新增项。
- [ ] 4.3 `08-WP04.T1` 为 LLM、Embedding、Rerank 和 KnowledgeAgent 实现受控真实故障 injector/fixture 与恢复检查，覆盖有限重试、deadline、usage、上游状态和模型身份。
- [ ] 4.4 `08-WP04.T1` 为五个专业 A2A endpoint 实现断连、超时、鉴权、Schema/required extension 故障 case，断言先 Get/Subscribe 原 Task、不盲重发且 messageId 幂等。
- [ ] 4.5 `08-WP04.T1` 为每个 Tool/Source 实现四类故障 case，并核对错误含 `sourceId/sourceKind/adapterId`、attempt、checkpoint、关联 ID 与 `logArtifactId`。
- [ ] 4.6 `08-WP04.T2` 实现 criticality assertion engine，分别验证 `MANDATORY`、有候选时 `MANDATORY_WHEN_CANDIDATES_EXIST`、允许/不允许继续的 `CONDITIONAL` 和未批准/已批准的 `OPTIONAL_APPROVED`。
- [ ] 4.7 `08-WP04.T2` 增加关键能力有限重试后 `FAILED`、允许缺失时 `ChainFailure + missingEvidence + limitations`、空结果不得误判故障以及父子 deadline/重试次数边界测试。
- [ ] 4.8 `08-WP04.T3` 实现调用路由审计，检测自动 Provider/Source failover、vector-only、关键词、固定排序/结果、跳过 Rerank 或未记录跳步，并证明 failure Run 的 `runPurpose` 不能进入质量分母。
- [ ] 4.9 `08-WP04.T1-T3` 在真实 Compose 逐 case 运行组件×故障×关键性矩阵，case 之间执行恢复/健康校验，输出 `outputs/phase8/08-WP04/cases/` 与 `criticality-matrix.json`。

## 5. 08-WP05：完成状态、并发与恢复矩阵

- [ ] 5.1 `08-WP05.T1` 实现只读 state invariant checker，关联 Agent、A2A Task、step、Run、lease、outbox 与 Artifact，检查合法转换、单活动 Run、attempt 单调、唯一终态和无重复副作用。
- [ ] 5.2 `08-WP05.T1` 增加同 Incident 双启动、旧版本 CAS、重复消息/事件、lease 未过期/过期接管和重复 Tool 副作用并发测试。
- [ ] 5.3 `08-WP05.T1` 增加取消与完成/失败竞争、客户端断开、Artifact 不存在/哈希错误/越权和迟到 Artifact 测试，验证权威终态、checkpoint 与审计一致。
- [ ] 5.4 `08-WP05.T1` 增加 SSE/metrics projector 独立故障与 outbox 有界重试/告警/幂等重放测试，证明不回滚 Incident、Run、Task、step 或 RCA 已提交状态。
- [ ] 5.5 `08-WP05.T2` 实现专业 Agent、客户端 stream、PostgreSQL、Artifact、模型、Prometheus、Jaeger、Toxiproxy 的受控中断/恢复器和每 case 前后环境健康检查。
- [ ] 5.6 `08-WP05.T2` 实现 checkpoint + lease + A2A Get/Subscribe 对账器，只有确认原 Task 不存在时才按相同 messageId 幂等重建，并记录接管 attempt 与上游状态。
- [ ] 5.7 `08-WP05.T2` 使用单调时钟验证重启后 60 秒内继续或明确 `FAILED`，覆盖已完成远端 Task、运行中 Task、未知 Task、状态不一致和恢复后依赖仍不可用。
- [ ] 5.8 `08-WP05.T3` 为 projector/outbox、权威状态和副作用建立重放前后快照 diff，验证顺序语义、重复消费和 poison event 耗尽错误预算后的告警。
- [ ] 5.9 `08-WP05.T1-T3` 运行完整状态/并发/恢复矩阵并执行统一残留故障扫描，输出 `outputs/phase8/08-WP05/cases/`、`recovery-concurrency-matrix.json` 和恢复时限报告。

## 6. 08-WP06：执行安全攻击面与泄密测试

- [ ] 6.1 `08-WP06.T1` 创建严格版本化 `security-catalog.yaml` 和覆盖生成器，为建设范围第 5 项每个攻击向量登记 caseId、入口、身份、资产、恶意 fixture、期望拒绝层/错误码和审计规则。
- [ ] 6.2 `08-WP06.T1` 将安全目录与 Agent/Tool/Source/Provider Registry、A2A Card、Artifact route 和数据库角色快照做差异测试，新增入口或缺失 case 时 fail closed。
- [ ] 6.3 `08-WP06.T2` 实现 Ground Truth schema/卷/DB 探测、跨 Incident/Task/Run、Artifact URL/文件越权、路径穿越、符号链接逃逸和超大文件 case，验证读取前拒绝且不泄漏存在性或宿主路径。
- [ ] 6.4 `08-WP06.T2` 实现 Prompt injection、任意 Shell、`HIGH_RISK`、未审批/拒绝/超时/已审批沙箱 case，核对有效权限不变和 UnsafeAction attempted/executed 审计。
- [ ] 6.5 `08-WP06.T2` 实现 PromQL、代码路径、模型 URL allowlist 绕过与 DNS/IP/重定向 SSRF case，验证统一安全中间件在网络调用前拒绝。
- [ ] 6.6 `08-WP06.T2` 实现 Agent Card/endpoint/Task 事件篡改、未知 required extension、跨 A2A 身份和 message/task 归属 case，验证状态变更前 fail closed。
- [ ] 6.7 `08-WP06.T3` 实现伪造 `sourceId/sourceKind`、联邦结果缺 `originSource`、跨 Source Evidence 冒充、Registry 覆盖和直接绕过安全中间件 case。
- [ ] 6.8 `08-WP06.T3` 实现直接写 Incident 权威表、越权 DB 角色、厂商 DTO 穿透 API/domain、connectionRef/Secret 枚举与错误响应泄漏 case。
- [ ] 6.9 `08-WP06.T4` 实现批次唯一 canary、Secret 摘要和禁止字段/大文本扫描器，覆盖日志、Trace、SSE、Actuator、测试报告、RCA、Artifact 与错误响应，报告只保存规则 ID、脱敏位置、URI 和 digest。
- [ ] 6.10 `08-WP06.T1-T4` 运行全部安全 case 和载体扫描，输出 `outputs/phase8/08-WP06/cases/`、`secret-scan/` 与 `security-gate.json`；任何硬禁令实际执行为 `FAILED`，必扫载体不可访问为 `BLOCKED/FAILED`。

## 7. 08-WP07：验证可观测性、账本和调查效率

- [ ] 7.1 `08-WP07.T1` 实现 correlation verifier，从 REST/SSE 错误沿 `requestId/traceId/runId/stepId/a2aTaskId/invocationId` 关联日志、Trace、状态、attempt、checkpoint 和 `logArtifactId`。
- [ ] 7.2 `08-WP07.T1` 为正常、重试、Tool/Source、A2A、Provider 和恢复失败各选真实样本，断言关联链完整、错误码/上游状态一致且所有字段已脱敏。
- [ ] 7.3 `08-WP07.T1` 实现 metrics descriptor/基数检查，拒绝 run/incident/URL/Prompt/error 原文等标签，并核对 Dashboard/告警需要的请求、延迟、错误、重试、等待、outbox、租约和健康指标。
- [ ] 7.4 `08-WP07.T2` 实现 ledger reconciler，从 Provider usage、价格/估算版本、Tool/A2A/状态事件复算 input/output Token、cost、调用、失败、重试、等待和 wall-clock。
- [ ] 7.5 `08-WP07.T2` 增加失败调用消耗、429 等待、Schema 修复 attempt、无 usage Provider、估算舍入和未解释差异测试，未解释差异必须使账本门禁失败。
- [ ] 7.6 `08-WP07.T3` 扩展 `opspilot-evaluation` 的 Efficiency 判定，逐 Run/逐 Agent 比较 Supervisor rounds≤12、专业 rounds≤8、Tool calls≤30、专业 A2A attempts≤10、wall-clock≤600s 和 `total_tokens`≤Profile budget。
- [ ] 7.7 `08-WP07.T3` 增加单维边界、任一 Run 超限、用户等待扣除、平均值掩盖、Token budget 缺失和禁止综合分数测试，并输出各维实际值/阈值/判定。
- [ ] 7.8 `08-WP07.T1-T3` 对 15 个正式 Run 执行遥测、账本和效率复核，输出 `outputs/phase8/08-WP07/telemetry/`、`ledger/`、`efficiency/` 及总门禁报告。

## 8. 08-WP08：执行固定负载性能与资源验证

- [ ] 8.1 `08-WP08.T1` 在 `scripts/performance/` 建立统一 config/runner/report 包，复用现有真实 retrieval probe 与最近秩分位数实现，记录 planned/actual start 以避免 coordinated omission。
- [ ] 8.2 `08-WP08.T1` 实现 50,000 active Chunk 数据装载/清理与完整性校验，固定 collection、model revision、`searchable=true` 过滤、黄金查询和数据/config SHA-256。
- [ ] 8.3 `08-WP08.T1` 实现非流式读 API 与已提交 SSE 重放首事件测量，使用独立预热/测量窗口并断言 p95 分别严格 `<500ms` 与 `<1s`。
- [ ] 8.4 `08-WP08.T1` 实现 20 检索 QPS 的精确 candidate recall 和完整 Embedding+召回+Rerank 测量，断言 p95 分别严格 `<300ms` 与 `<2s` 且模型/候选/过滤身份正确。
- [ ] 8.5 `08-WP08.T1` 实现单 Incident E2E 与进程重启恢复测量，断言 p95 `<10min`，并在 60 秒内完成 Task/checkpoint 对账后继续或明确失败。
- [ ] 8.6 `08-WP08.T2` 实现宿主/容器 CPU、内存、GPU、OOM、JVM、线程、HTTP/数据库连接和权威状态 collector，在 batch 前、每 Run、恢复后与最终稳定窗口采样。
- [ ] 8.7 `08-WP08.T2` 实现 offered/achieved QPS、最小样本、预热排除、p50/p95/p99、吞吐、错误率与原始样本复算器；达不到目标负载或配置漂移时结果无效而非通过。
- [ ] 8.8 `08-WP08.T3` 冻结资源增长/连接回落容差并实现 15 Run 前后比较，检测 OOM、连接泄漏、状态丢失和不可解释资源增长，禁止运行后选择窗口或容差。
- [ ] 8.9 `08-WP08.T1-T3` 在固定开发机运行完整性能 suite，输出 `outputs/phase8/08-WP08/raw/`、`performance-gate.json`、`resource-stability.json` 和硬件身份报告，并标明非生产 SLO 边界。

## 9. 08-WP09：条件 ANN、可移植性与发布证据

- [ ] 9.1 `08-WP09.T1` 实现 ANN decision reader，校验 WP08 精确检索报告、snapshot/query/filter/revision digest 与有效负载；p95 `<300ms` 时生成“保持精确检索”的 `NOT_APPLICABLE` ADR 并检查无新增 ANN 索引。
- [ ] 9.2 `08-WP09.T1` 仅在精确检索 p95 `≥300ms` 时创建 HNSW/IVFFlat 对照计划，强制复用同一 revision、collection、`searchable=true` 过滤、黄金查询、20 QPS 与机器身份。
- [ ] 9.3 `08-WP09.T2` 实现 ANN benchmark，分别采集 Recall@K、p50/p95/p99、索引大小、构建时间、WAL、写吞吐、内存和候选完整性，先判质量/过滤正确性再比较性能。
- [ ] 9.4 `08-WP09.T2` 实现 HNSW/IVFFlat 的创建、校验、切换、黄金查询、回滚与删除演练，测试中断恢复、失败切换和临时索引无残留。
- [ ] 9.5 `08-WP09.T3` 建立禁用 Java Code Analyzer/Maven Sandbox Adapter 的真实诊断 suite，断言仍生成受限报告且不包含代码文件、行号、补丁或代码级根因。
- [ ] 9.6 `08-WP09.T3` 增加测试作用域 Loki/Tempo Source Adapter，实现既有 Port/Registry/规范化合同，并对 core API、四层状态、Evidence/RCA schema/表和 A2A skill major version 执行无变化 diff。
- [ ] 9.7 `08-WP09.T4` 定义统一 evidence envelope 与严格校验器，使每层报告记录 commit、Workflow/run identity、suite version、开始/结束、通过/失败/跳过、Artifact URI/大小/SHA-256，并拒绝用单元报告占用 E2E suiteId。
- [ ] 9.8 `08-WP09.T4` 生成 `outputs/phase8/08-WP09/evidence-index.json`，校验 WP01～WP09 的必需报告、原始证据引用、digest 和状态折叠，缺 Runner/Secret/真实模型必须保留 `BLOCKED`。
- [ ] 9.9 `08-WP09.T4` 新增通用 `scripts/release/generate_release_manifest.py`，复用冻结 `release-manifest.schema.json` 和既有 8 类质量门禁；保留阶段 0 脚本并增加 `FAILED/BLOCKED/NOT_APPLICABLE` 与禁止自动部署测试。
- [ ] 9.10 `08-WP09.T1-T4` 运行 ANN 条件分支、可移植性、合同 diff、evidence index 与 Release Manifest 严格校验，输出 `outputs/phase8/08-WP09/`；仅当全部必需门禁通过时生成 `READY_FOR_MANUAL_DEPLOYMENT`，否则如实生成 `FAILED/BLOCKED`。
