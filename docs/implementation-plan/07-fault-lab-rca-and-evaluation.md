# 阶段 07：Fault Lab、三个场景、RCA 与确定性评测

## 目标

实现可复现的故障实验链、三个冻结场景、Agent input/Ground Truth 隔离、结构化 RCA 与不依赖 LLM-as-a-Judge 的确定性 Evaluation。

## 前置门禁

- Sample、Source Adapter、真实多 Agent、RAG、Artifact 与产品 API/SSE 完整可用。
- `scenario.schema.json`、`ground-truth.schema.json`、`rca.schema.json`、`evaluation-profile.schema.json` 及示例保持冻结。

## 建设范围

1. 用 Python 3.11+ 实现 ScenarioLoader、EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector、TicketGenerator、GroundTruthGenerator、DatasetWriter、ScenarioValidator。
2. 场景统一执行“重置 → 健康 → 基线 → 注入 → 持续负载 → 采集 → 恢复 → 导出 → 校验”，任何失败均在 `finally` 恢复环境。
3. 实现 `dependency-latency-inventory/1.0.0`：Toxiproxy 下游延迟 `3000ms ± 100ms`、注入 120 秒，在总计 240 秒的持续负载中严格校验基线、Trace 父子关系、故障成立/恢复条件、必选/辅助 Evidence 和禁止误判。
4. 实现 `database-pool-exhausted-order/1.0.0`：4 个受控长事务、90 秒持有、2 秒 connection timeout、故障注入窗 120 秒和总计 240 秒持续负载；不得停止共享 PostgreSQL。
5. 实现 `service-instance-stopped-inventory/1.0.0`：由 Fault Lab 停止原 inventory 容器 90 秒、不删卷，在总计 240 秒的持续负载中验证 readiness、连接失败、Prometheus target 和恢复。
6. 每次执行生成唯一 datasetRunId，记录 commit、Compose 摘要、镜像 digest、模型配置摘要、随机种子、UTC 三窗口和全部 Artifact SHA-256。
7. 输出固定数据集目录：`input/` 只读给 Agent，`ground-truth/` 只给 Fault Lab/Evaluation，`execution/` 保存执行日志与时间线且其路径和敏感内容均不挂载或暴露给 Agent。数据库与卷凭证同步隔离。
8. 由确定性规则生成 `source.type.fact` Evidence Code；必须同时匹配服务、时间窗和 predicate，不使用自然语言相似度或模型生成代码。
9. 实现结构化 RCA（多个 Hypothesis、支持/冲突 Evidence、验证、结论等级、rootCauseCode 可空、修复建议）；分析完成进入 `GENERATING_REPORT` 时封账，随后按 runId 直接读取数据库中的全部 Evidence/Hypothesis/关系/验证结果和 missingEvidence 组成 RCA，不建立重复输入快照表，再从同一对象渲染 JSON 和 Markdown。
10. 实现独立 Evaluation 进程与八类指标：RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency、CitationValidity。
11. 实现版本化 Evaluation Profile、单 Run 去重、macro average、报告 Artifact 和 `opspilot.evaluation_result` 写回；Evaluation 只能读 Ground Truth，Agent 永远不能读。

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 07-WP01 | Scenario/Ground Truth/RCA/Evaluation Profile Schema 与场景设计 | 阶段 01 合同校验链 |
| 07-WP02 | Fault Lab 所有权、统一场景执行序列和恢复规则 | 07-WP01，阶段 04 故障入口 |
| 07-WP03 | datasetRunId、三时间窗、Artifact 清单和 Ground Truth 隔离 | 07-WP01、07-WP02，阶段 03 Artifact |
| 07-WP04 | 延迟场景 YAML/Ground Truth 示例及冻结参数 | 07-WP02、07-WP03，阶段 04 Toxiproxy/遥测 |
| 07-WP05 | 连接池场景 YAML/Ground Truth 示例及冻结参数 | 07-WP02、07-WP03，阶段 04 Sample/遥测 |
| 07-WP06 | 实例停止场景 YAML/Ground Truth 示例及冻结参数 | 07-WP02、07-WP03，阶段 04 外部容器控制 |
| 07-WP07 | Ground Truth Schema、Evidence Code 确定性匹配规则 | 07-WP03～07-WP06 |
| 07-WP08 | RCA Schema、引用与受限结论规则 | 阶段 06 Agent/RCA 链，07-WP07 |
| 07-WP09 | Evaluation Profile、八指标公式和 Evaluation 隔离 | 07-WP07、07-WP08 |

### 07-WP01：建立 Fault Lab 工程与冻结合同

- **07-WP01.T1**：建立 Python 3.11+ 工程、依赖锁、CLI 和结构化配置，加载并严格校验 Scenario、Ground Truth、RCA、Evaluation Profile Schema。
- **07-WP01.T2**：实现 ScenarioLoader/Validator，拒绝未知字段、版本不匹配、非法时间窗、缺 required source/evidence 和未登记场景。
- **07-WP01.T3**：实现 EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector 等组件的窄接口和显式 Registry。
- **目标文件**：`fault-lab/pyproject.toml`、`fault_lab/**`、四类 machine contract 的 loader/validator 和测试 fixture。
- **验证与证据**：锁文件、Schema 正/负样例、未知版本/字段拒绝和组件装配测试。

### 07-WP02：实现可恢复的场景执行状态机

- **07-WP02.T1**：按“重置 → 健康 → 基线 → 注入 → 持续负载 → 采集 → 恢复 → 导出 → 校验”实现阶段化 runner 和 UTC 时间线。
- **07-WP02.T2**：每步保存开始/结束、输入摘要、输出 Artifact、失败原因和 checkpoint；失败/取消始终在 `finally` 进入恢复。
- **07-WP02.T3**：恢复后重新检查服务、代理、故障开关和数据状态；恢复失败单独记录，不覆盖原始失败。
- **07-WP02.T4**：Fault Lab 独占 Docker 控制权，Agent/Tool 不获得 Docker socket。
- **目标文件**：scenario runner/state、environment adapters、checkpoint/timeline、cleanup/recovery policy。
- **验证与证据**：逐阶段故障注入、取消、重复 reset、恢复失败双错误和无残留故障测试。

### 07-WP03：实现统一数据集、时间窗与隔离

- **07-WP03.T1**：每次运行生成唯一 `datasetRunId`、随机种子和 baseline/fault/recovery 三个 UTC 时间窗。
- **07-WP03.T2**：写入 commit、Compose digest、镜像/模型配置 digest、Scenario version、Artifact URI/SHA-256 和执行日志。
- **07-WP03.T3**：生成固定 `input/`、`ground-truth/`、`execution/` 目录；Agent 只读访问 input，Ground Truth/execution 路径、卷和凭证均不可见。
- **07-WP03.T4**：校验 Artifact 哈希、时间窗顺序、required 文件、datasetRunId 一致和跨目录引用。
- **目标文件**：DatasetWriter/Validator、manifest/timeline、Compose mount/credential 配置、隔离测试。
- **验证与证据**：数据集清单、哈希复算、Agent 视角目录/凭证探测和跨 Run 污染测试。

### 07-WP04：实现库存依赖延迟场景

- **07-WP04.T1**：实现 `dependency-latency-inventory/1.0.0`，通过 Toxiproxy 注入 `3000ms ± 100ms` 下游延迟 120 秒，持续负载总计 240 秒。
- **07-WP04.T2**：在注入前验证健康/延迟基线，在故障窗验证代理配置、请求退化和 Trace 父子关系，在恢复窗验证延迟回落。
- **07-WP04.T3**：采集设计冻结的必选/辅助 Evidence，生成对应 Ground Truth，并显式校验禁止误判项。
- **目标文件**：场景 YAML、Toxiproxy injector、load profile、collector、Ground Truth fixture。
- **验证与证据**：参数实测、三窗口断言、Trace 拓扑、场景校验报告和可重复运行记录。

### 07-WP05：实现订单数据库连接池耗尽场景

- **07-WP05.T1**：实现 `database-pool-exhausted-order/1.0.0`：4 个长事务、持有 90 秒、connection timeout 2 秒、注入窗 120 秒、持续负载 240 秒。
- **07-WP05.T2**：验证 `maximumPoolSize=4`、长事务全部占用连接、等待/超时指标和订单请求退化；禁止停止共享 PostgreSQL。
- **07-WP05.T3**：恢复长事务后验证池状态、请求成功率与数据库健康，采集 required/auxiliary Evidence 和禁止误判项。
- **目标文件**：场景 YAML、long-transaction injector、load/collector、Ground Truth fixture。
- **验证与证据**：Hikari/数据库/请求时间线、连接数断言、恢复结果和场景校验报告。

### 07-WP06：实现库存实例停止场景

- **07-WP06.T1**：实现 `service-instance-stopped-inventory/1.0.0`，由 Fault Lab 停止原容器 90 秒且不删除卷，持续负载总计 240 秒。
- **07-WP06.T2**：记录被停止容器身份并恢复同一服务；故障窗验证 readiness、连接失败、网关/订单影响和 Prometheus target。
- **07-WP06.T3**：恢复后验证服务健康、target UP、业务调用和数据保留，采集冻结的 Evidence/Ground Truth。
- **目标文件**：场景 YAML、external container injector、health/target collector、Ground Truth fixture。
- **验证与证据**：容器事件、卷不变证明、三窗口健康/业务断言及恢复报告。

### 07-WP07：生成确定性 Evidence Code 与 Ground Truth

- **07-WP07.T1**：由规则生成 `source.type.fact` Evidence Code，要求 source、Resource、服务、时间窗和 predicate 同时匹配。
- **07-WP07.T2**：GroundTruthGenerator 只消费执行事实和冻结场景定义，不调用模型、不使用文本相似度。
- **07-WP07.T3**：验证 required/auxiliary/forbidden Evidence、Root Cause Code 和当前 datasetRunId；写入后只读并计算摘要。
- **目标文件**：evidence code rules、GroundTruthGenerator/Validator、三场景 ground-truth fixture。
- **验证与证据**：边界时间、错误服务、错误 Source、相似文本误匹配等负向测试和规则覆盖报告。

### 07-WP08：实现结构化 RCA 与双格式渲染

- **07-WP08.T1**：实现多个 Hypothesis、支持/冲突 Evidence、验证动作、结论等级、可空 rootCauseCode、修复建议和 missingEvidence。
- **07-WP08.T2**：校验所有引用属于当前 Run 且 Evidence/Artifact 可访问；证据不足允许 `PARTIAL` 或 `INCONCLUSIVE/rootCause=null`。
- **07-WP08.T3**：只允许已封账且状态为 `GENERATING_REPORT` 的 Run 生成报告；按 runId 在短只读 `REPEATABLE READ` 事务中直接查询全部 Evidence、Hypothesis、支持/冲突关系、验证结果和 missingEvidence，校验 `runVersion/analysisSealedAt` 后在事务外调用模型，不创建 `RcaGenerationSnapshot` 表。
- **07-WP08.T4**：保存与 `runId + runVersion` 绑定的 RCA 元数据；只从同一个通过 Schema 的 RCA 对象渲染 JSON/Markdown，禁止两套内容生成路径。生成失败重试继续使用同一封账数据；需要新证据时创建新 Run。
- **目标文件**：RCA domain/Schema mapper、sealed-run query service、citation validator、RCA metadata repository、JSON/Markdown renderer。
- **验证与证据**：Schema、跨 Run 引用、冲突证据、受限结论、封账后迟到 Evidence 拒绝、查询全量性、报告重试一致性、模型调用不持有数据库事务和双格式语义一致测试。

### 07-WP09：实现独立确定性 Evaluation

- **07-WP09.T1**：建立独立 Evaluation 进程和只读 Ground Truth 凭证；Agent 服务身份、卷、API 和日志均无访问路径。
- **07-WP09.T2**：严格按设计公式计算 RootCauseTop1Accuracy、EvidenceRecall/Precision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency、CitationValidity。
- **07-WP09.T3**：实现版本化 Profile、单 Run 去重、三场景 macro average、JSON/Markdown 报告 Artifact 和 `opspilot.evaluation_result` 写回。
- **07-WP09.T4**：把一次真实 Agent Run 与 Ground Truth 对齐，验证指标可由原始事件/Evidence 独立复算。
- **目标文件**：`opspilot-evaluation/**`、metric calculators、Profile loader、report renderer/writer。
- **验证与证据**：每项指标黄金样例、除零/重复 Run、macro average、权限隔离与复算报告。

## 阶段内执行顺序

1. 先完成 07-WP01～07-WP03，冻结 runner、数据集和隔离基础。
2. 07-WP04～07-WP06 共用同一 runner/collector，并逐场景通过基线、故障、恢复合同。
3. 07-WP07 固化 Ground Truth 后，07-WP08、07-WP09 接入真实 Agent 输出并完成正常链路 E2E。

## 测试与证据矩阵

| 验证层 | 必测内容 | 阶段证据 |
| --- | --- | --- |
| Schema/单元 | 四类合同、Evidence Code、八项指标、RCA 封账查询/renderer | 黄金 fixture 与测试报告 |
| 场景集成 | 三场景参数、三时间窗、required/forbidden Evidence、恢复 | 每场景数据集和 validator 报告 |
| 隔离/安全 | Agent 无 Ground Truth/execution/卷/凭证/Docker socket | 越权负向测试 |
| E2E | Fault Lab → Agent 调查 → RCA → Evaluation | 三场景各一次正常运行的关联 Artifact |
| 可复现 | 相同版本/种子重跑、哈希与环境摘要 | dataset manifest 与差异报告 |

## 主要输出

- `fault-lab` 场景运行器、三个 Scenario YAML、Ground Truth、流量和采集器；
- 可复现的数据集、时间线、Artifact 清单和校验报告；
- RCA 领域输出、JSON/Markdown 渲染与引用验证；
- `opspilot-evaluation`、8 类指标、Profile 和 JSON/Markdown 评测报告；
- 三场景正常链路的 E2E 测试。

## 完成门禁

- 三场景分别满足第 25.3–25.5 节全部注入、基线、故障成立和恢复合同，数据集有效率 100%。
- Ground Truth 通过 Schema，Agent 角色/卷/API/日志/Prompt 无访问路径；越权测试失败关闭。
- 每条必选 Evidence 可追溯到 Source/Batch/Record/Artifact，并使用确定性 evidenceCode。
- RCA 只从已封账 Run 的数据库全量 Evidence/Hypothesis/关系/验证结果生成，不存在第二份输入快照；JSON/Markdown 同源、Schema 有效、引用属于当前 Run，证据不足时允许 `PARTIAL` 或 `INCONCLUSIVE/rootCause=null`。
- 八类指标严格按第 25.7 节公式计算，不使用 LLM 判断或文本相似度。
- 每次执行失败都恢复环境，不删除数据卷，不让 Agent 获得 Docker socket。

## 明确不做

- 本阶段完成场景与评测能力，但不宣称已通过发布所需的每场景 5 次聚合阈值；15 次发布运行属于阶段 08。
- 不增加第四场景或更改三个场景的参数、required Evidence、Root Cause Code 和阈值。

## 设计依据

- [Fault Lab 与 RCA/评测边界](../design/opspilot-system-design/02-modules-and-boundaries.md)
- [测试策略](../design/opspilot-system-design/09-test-strategy.md)
- [场景与确定性评测](../design/opspilot-system-design/14-scenarios-and-deterministic-evaluation.md)
