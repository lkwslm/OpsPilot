## 1. 07-WP01：建立 Fault Lab 工程与冻结合同

- [x] 1.1 `07-WP01.T1` 创建 `fault-lab/pyproject.toml`、Python 3.11+ 包结构、测试入口、依赖锁和单一 CLI，运行安装与 `--help` smoke 并保存锁文件证据。
- [x] 1.2 `07-WP01.T1` 实现四类 machine contract 的版本化 loader，直接加载 `docs/design/contracts/schemas/{scenario,ground-truth,rca,evaluation-profile}.schema.json` 并保持未知字段 fail closed。
- [x] 1.3 `07-WP01.T1` 为四类 Schema 建立合法/缺字段/未知字段/未知版本/格式错误 fixture，运行 `python -m pytest fault-lab/tests/contracts` 并输出 `outputs/phase7/07-WP01/schema-contract-report.json`。
- [x] 1.4 `07-WP01.T2` 实现 ScenarioLoader/ScenarioValidator，对非法 baseline/fault/recovery 窗口、缺 required source/evidence、groundTruthRef 不匹配和未登记 `scenarioId/scenarioVersion` 返回稳定错误。
- [x] 1.5 `07-WP01.T2` 添加场景版本、时间窗、required/one-of/forbidden 集合和跨文件版本一致性的正负合同测试，证明校验失败时无环境副作用。
- [x] 1.6 `07-WP01.T3` 定义 ScenarioLoader、EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector、TicketGenerator、GroundTruthGenerator、DatasetWriter、ScenarioValidator 的窄类型接口。
- [x] 1.7 `07-WP01.T3` 实现显式组件 Registry、能力键/版本选择、重复或缺失注册拒绝和装配后冻结，禁止动态 import 或名称反射绕过 Registry。
- [x] 1.8 `07-WP01.T1-T3` 运行 Fault Lab 全部合同与装配测试，把 CLI、依赖版本、Schema 覆盖和 Registry 清单汇总到 `outputs/phase7/07-WP01/fault-lab-foundation-report.json`；任一冻结合同差异时阻塞后续工作包。

## 2. 07-WP02：实现可恢复的场景执行状态机

- [x] 2.1 `07-WP02.T1` 实现 `RESETTING → HEALTH_CHECKING → BASELINING → INJECTING → LOADING/COLLECTING → RECOVERING → EXPORTING → VALIDATING` 的单一 runner 状态机和合法转换表。
- [x] 2.2 `07-WP02.T1` 实现基于 UTC 的阶段开始/结束和 baseline/fault/recovery 时间线，保证前置 predicate 未满足时后续注入或采集不得启动。
- [x] 2.3 `07-WP02.T2` 为每个阶段持久化输入摘要、输出 Artifact、checkpoint、尝试号和失败原因，测试 checkpoint 写入失败先于外部副作用 fail closed。
- [x] 2.4 `07-WP02.T2` 在 runner 顶层实现 `finally` 恢复和取消传播，覆盖重置、健康、基线、注入、负载、采集、导出、校验各阶段的失败注入。
- [x] 2.5 `07-WP02.T3` 实现幂等 cleanup/recovery policy，恢复后核验 toxic、受控事务、容器身份、服务 readiness、Prometheus target、故障开关和数据状态。
- [x] 2.6 `07-WP02.T3` 对 primary failure 与 recovery failure 建立独立错误记录和最终状态规则，测试恢复失败不覆盖原始失败且重复 reset 不产生新残留。
- [x] 2.7 `07-WP02.T4` 在 Compose/凭证/网络层只向 Fault Lab 授予白名单 Docker 控制能力，明确移除 Agent、Tool、产品 API 和 Evaluation 的 Docker socket、代理端点及控制凭证路径。
- [x] 2.8 `07-WP02.T1-T4` 运行逐阶段失败、取消、重复 reset、双错误和 Agent Docker 越权测试，输出 `outputs/phase7/07-WP02/scenario-state-recovery-matrix.json` 与无残留故障证明。

## 3. 07-WP03：实现统一数据集、时间窗与隔离

- [x] 3.1 `07-WP03.T1` 为每次执行生成 UUID `datasetRunId` 和显式随机种子，并从 runner 时间线派生有序的 baseline/fault/recovery UTC 窗口。
- [x] 3.2 `07-WP03.T2` 定义并实现 dataset manifest/timeline，记录 Git commit、规范化 Compose digest、镜像 digest、去 Secret 模型配置 digest、Scenario version 和 Artifact URI/大小/SHA-256。
- [x] 3.3 `07-WP03.T2` 实现 Artifact 写入、哈希复算和临时目录到只读数据集的原子发布，失败/取消时保留可审计 execution 但不发布不完整 input。
- [x] 3.4 `07-WP03.T3` 创建固定 `input/`、`ground-truth/`、`execution/` 目录和独立卷/服务身份/数据库角色，Agent 仅以只读方式挂载 `input/`。
- [x] 3.5 `07-WP03.T3` 从 Agent 可见的挂载、环境变量、API、日志、Prompt、数据库授权和错误响应中移除 ground-truth/execution 路径、内容、卷名与凭证。
- [x] 3.6 `07-WP03.T4` 实现 DatasetValidator，对 required 文件、三窗口顺序、datasetRunId 跨文件一致、Artifact 哈希、URI 和规范化后跨目录引用进行 fail-closed 校验。
- [x] 3.7 `07-WP03.T1-T4` 运行哈希篡改、路径穿越、符号链接、跨 Run 污染及 Agent 目录/凭证探测测试，输出 `outputs/phase7/07-WP03/dataset-isolation-report.json` 和可复现 manifest 差异报告。

## 4. 07-WP04：实现库存依赖延迟场景

- [x] 4.1 `07-WP04.T1` 按冻结示例实现 `dependency-latency-inventory/1.0.0` Scenario YAML、Ground Truth fixture 和版本注册，拒绝任何参数漂移。
- [x] 4.2 `07-WP04.T1` 实现带 datasetRunId 标签的 Toxiproxy latency injector，将 order→inventory 下游延迟锁定为 `3000ms ± 100ms`、注入 120 秒，并保证恢复删除本次 toxic。
- [x] 4.3 `07-WP04.T1` 实现 240 秒持续订单负载 profile 和统计 Artifact，保证 baseline、fault、recovery 三窗口请求可按事件时间独立聚合。
- [x] 4.4 `07-WP04.T2` 实现基线 60 秒、至少 100 请求、成功率 ≥99%、p95<500ms，以及故障窗至少 30 请求、client span p95≥2500ms、inventory CPU/DB 未饱和的 predicate。
- [x] 4.5 `07-WP04.T2` 校验 order/inventory client span 的父子关系和 fault window 归属，并验证移除 toxic 后 60 秒内成功率 ≥99%、p95<700ms。
- [x] 4.6 `07-WP04.T3` 采集两项 required、one-of auxiliary Evidence 和 forbidden root cause 事实，生成 Ground Truth 并覆盖错误服务、错误窗口和禁止误判负向测试。
- [x] 4.7 `07-WP04.T1-T3` 在真实 Compose/Toxiproxy/遥测环境运行场景至少两次，输出 `outputs/phase7/07-WP04/` 下的参数实测、Trace 拓扑、三窗口断言、validator 和重复运行报告。

## 5. 07-WP05：实现订单数据库连接池耗尽场景

- [x] 5.1 `07-WP05.T1` 按冻结示例实现 `database-pool-exhausted-order/1.0.0` Scenario YAML、Ground Truth fixture 和版本注册，锁定池/事务/超时/窗口参数。
- [x] 5.2 `07-WP05.T1` 实现只面向 Sample test profile 的 long-transaction injector，建立 4 个受控事务并持有 90 秒，所有退出路径释放事务。
- [x] 5.3 `07-WP05.T1` 实现 120 秒故障窗和 240 秒持续订单负载，验证 `maximumPoolSize=4`、`connectionTimeout=2000ms` 的运行配置摘要。
- [x] 5.4 `07-WP05.T2` 实现基线 pending=0、active<max、至少 100 请求且成功率 ≥99%，以及故障窗连续 15 秒 active=max、pending>0 和真实 connection timeout 的 predicate。
- [x] 5.5 `07-WP05.T2` 在 injector 和 Docker/数据库策略层禁止停止、重启或破坏共享 PostgreSQL，并用动作计划与真实越权请求证明 fail closed。
- [x] 5.6 `07-WP05.T3` 验证释放事务后 30 秒内 pending=0/active<max、60 秒内成功率 ≥99%，采集 required/auxiliary Evidence 并排除实例停止和网络延迟误判。
- [x] 5.7 `07-WP05.T1-T3` 在真实 Sample/Hikari/PostgreSQL/遥测环境运行场景至少两次，输出 `outputs/phase7/07-WP05/` 下的池/数据库/请求时间线、连接数断言、恢复和 validator 报告。

## 6. 07-WP06：实现库存实例停止场景

- [x] 6.1 `07-WP06.T1` 按冻结示例实现 `service-instance-stopped-inventory/1.0.0` Scenario YAML、Ground Truth fixture 和版本注册，锁定停机/负载/恢复参数。
- [x] 6.2 `07-WP06.T1` 实现 external container injector，记录原 inventory 容器、镜像、配置和卷 identity，停止 90 秒且拒绝 `down -v`、volume remove 及等价删除动作。
- [x] 6.3 `07-WP06.T1` 实现 240 秒订单与库存持续负载，并保证恢复启动原配置服务而非创建使用空卷的替代环境。
- [x] 6.4 `07-WP06.T2` 实现基线 readiness/Prometheus target UP、成功率 ≥99%，以及故障窗 readiness 不可达、连接拒绝或 503、target DOWN 连续至少 30 秒的 predicate。
- [x] 6.5 `07-WP06.T2` 记录网关/订单受影响范围、容器事件和健康时间线，验证瞬时 readiness 抖动或单纯慢响应不构成实例停止。
- [x] 6.6 `07-WP06.T3` 验证恢复后 60 秒内 readiness/target UP、随后 60 秒成功率 ≥99% 和数据/卷不变，采集 required/one-of Evidence 与 forbidden 事实。
- [x] 6.7 `07-WP06.T1-T3` 在真实 Compose/Prometheus/业务环境运行场景至少两次，输出 `outputs/phase7/07-WP06/` 下的容器事件、卷不变证明、三窗口健康/业务断言和恢复报告。

## 7. 07-WP07：生成确定性 Evidence Code 与 Ground Truth

- [x] 7.1 `07-WP07.T1` 定义版本化 `source.type.fact` 规则模型和编译器，要求 Source type、Resource、service、UTC window 与结构化 predicate 同时匹配。
- [x] 7.2 `07-WP07.T1` 实现 Evidence Code matcher 的唯一归码、同 code 多 Artifact 去重和冲突规则 fail closed，禁止读取自然语言摘要做相似度匹配。
- [x] 7.3 `07-WP07.T1` 为边界时间、错误 service/Resource/Source、predicate 不满足、相似文本和规则冲突建立负向 fixture。
- [x] 7.4 `07-WP07.T2` 实现 GroundTruthGenerator，只消费冻结场景定义、实际 injection/recovery fact 和哈希有效的采集结果，依赖图中不得存在模型客户端或 Agent/RCA 输入。
- [x] 7.5 `07-WP07.T3` 实现 GroundTruthValidator，校验 required/one-of/forbidden Evidence、Root Cause Code、Tool/action 集合、dataset validity、recovery predicate 和当前 datasetRunId 一致性。
- [x] 7.6 `07-WP07.T3` 实现 Ground Truth 原子写入、写后只读、内容 digest 与受限访问审计，拒绝跨 Run 引用和已发布内容修改。
- [x] 7.7 `07-WP07.T1-T3` 对三个场景运行规则覆盖和 Ground Truth Schema/隔离测试，输出 `outputs/phase7/07-WP07/evidence-code-rule-coverage.json` 与 Ground Truth digest 清单。

## 8. 07-WP08：实现结构化 RCA 与双格式渲染

- [x] 8.1 `07-WP08.T1` 在 `opspilot-core` 扩展 RCA domain/Schema mapper，表达多个 Hypothesis、支持/冲突 Evidence、Verification、结论等级、可空 rootCause、修复建议、limitations 和 missingEvidence。
- [x] 8.2 `07-WP08.T1` 实现 `CONCLUSIVE|PARTIAL|INCONCLUSIVE` 不变量，覆盖证据不足时 `INCONCLUSIVE/rootCause=null`、PARTIAL 有依据根因及伪造确定结论拒绝。
- [x] 8.3 `07-WP08.T2` 实现 CitationValidator，逐项验证对象存在、当前 Run 归属、访问权、Artifact SHA-256、incident window 重叠和 claim evidenceCode 一致。
- [x] 8.4 `07-WP08.T3` 为 `GENERATING_REPORT`/analysis seal/runVersion 建立报告前置门禁，未封账、封账竞争和封账后迟到 Evidence 均不得进入 RCA 输入。
- [x] 8.5 `07-WP08.T3` 在短只读 `REPEATABLE READ` 事务中按 `runId + runVersion` 查询全部 Evidence/Hypothesis/支持冲突关系/Verification/missingEvidence，并记录规范化输入 digest。
- [x] 8.6 `07-WP08.T3` 增加查询全量性与事务边界测试，证明模型调用发生在事务外，代码和 migration 中不存在 `RcaGenerationSnapshot` 或等价重复输入表。
- [x] 8.7 `07-WP08.T4` 添加 RCA metadata migration/Repository，以 `(runId,runVersion)` 唯一绑定 Schema version、输入/对象 digest、analysisSealedAt 和 JSON/Markdown Artifact。
- [x] 8.8 `07-WP08.T4` 实现仅消费同一个 Schema 有效 RCA 对象的 JSON/Markdown renderer，校验两格式结论、Hypothesis、引用、动作和限制的规范化语义一致。
- [x] 8.9 `07-WP08.T1-T4` 运行 `./mvnw -pl opspilot-core,opspilot-adapters/persistence-postgres,opspilot-server -am test`，输出 `outputs/phase7/07-WP08/rca-sealed-input-and-rendering-report.json`，覆盖跨 Run 引用、冲突证据、迟到写入、查询全量、重试 digest 和双格式一致性。

## 9. 07-WP09：实现独立确定性 Evaluation

- [x] 9.1 `07-WP09.T1` 扩展阶段 00 的 `opspilot-evaluation` 占位 Maven 模块，新增独立进程入口和 core 只读合同依赖，并接入 Compose，不复用 Agent/Supervisor 运行身份。
- [x] 9.2 `07-WP09.T1` 创建 Evaluation 专用 Ground Truth 只读卷/数据库角色/Artifact 凭证与网络策略，运行 Agent 对文件、DB、API、日志、Prompt 的越权矩阵。
- [x] 9.3 `07-WP09.T2` 实现 RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision 和 ToolSelectionAccuracy calculator，保存 code 去重集合、TP/FP/FN 与分子/分母。
- [x] 9.4 `07-WP09.T2` 实现 TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency 和 CitationValidity calculator，保留 attempted/executed、安全硬门禁和 `notApplicable` 语义。
- [x] 9.5 `07-WP09.T2` 为八项指标建立黄金样例、除零、重复 code、跨 Run/不存在引用、INCONCLUSIVE、禁止动作和预算边界测试，证明无模型或文本相似度依赖。
- [x] 9.6 `07-WP09.T3` 实现版本化 Evaluation Profile loader/validator 和配置快照；Profile 降阈值不能覆盖或删除旧版本结果。
- [x] 9.7 `07-WP09.T3` 添加 `opspilot.evaluation_result` migration/Repository，以 `(runId,profileId,profileVersion)` 幂等去重，并分别计算场景聚合和三场景等权 macro average。
- [x] 9.8 `07-WP09.T3` 从同一 EvaluationResult 渲染 JSON/Markdown，写入报告 Artifact 与结果表，校验两格式、数据库和 Artifact digest 一致。
- [x] 9.9 `07-WP09.T4` 实现从原始 Incident/Run、RCA、Evidence、Tool/A2A/Usage 事件和 Ground Truth 独立复算的 verifier，差异时使 Evaluation 结果无效。
- [x] 9.10 `07-WP09.T4` 在三个冻结场景中各执行一次 `Fault Lab → Agent 调查 → 封账 RCA → Evaluation` 真实正常链路，验证每项指标可复算且关联 Artifact 完整；明确标记这 3 次为阶段 07 功能验收，不宣称满足阶段 08 的 15 Run 发布阈值。
- [x] 9.11 `07-WP09.T1-T4` 运行 `./mvnw -pl opspilot-evaluation -am test`、Fault Lab/Evaluation 权限测试和三场景 E2E，输出 `outputs/phase7/07-WP09/` 指标黄金报告、macro average、隔离矩阵、复算结果及 `outputs/phase7/phase7-gate-summary.json`；任一数据集恢复、隔离、RCA 或确定性指标门禁失败时不得标记阶段完成。
