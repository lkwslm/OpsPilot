## Context

阶段 06 已提供六进程 Agent/A2A 产品链路、Artifact 接收、Evidence/Hypothesis 持久化、analysis seal 与 `RcaReportService` 基础；阶段 00 只留下少量 Java Fault Lab Adapter 和 `opspilot-evaluation` 占位模块，尚未形成阶段 07 所需的 Python runner 与确定性评测实现。四类 JSON Schema 与三个 Scenario/Ground Truth 示例已冻结在 `docs/design/contracts/`，阶段 07 只能按合同实现，不能反向修改参数以适应代码。

本设计跨 Python Fault Lab、Java core/persistence/evaluation、Compose 权限边界和真实 Sample/可观测性环境。事实源顺序为 machine contract、设计状态/不变量、阶段计划；前置 Sample、Source Adapter、真实多 Agent、RAG、Artifact、产品 API/SSE 任一不可用时，相关真实 E2E 保持阻塞，不能以 Fake 或局部测试替代。

## Goals / Non-Goals

**Goals:**

- 用同一 runner 和组件协议执行三个冻结场景，并让每个退出路径都可审计、可恢复。
- 产出身份唯一、环境可追溯、Artifact 可校验且对 Agent 最小暴露的数据集。
- 让 Evidence Code、Ground Truth 和八类 Evaluation 指标完全由版本化确定性规则复算。
- 在不复制 RCA 输入快照的前提下，从封账 Run 形成一个 Schema 有效对象及一致的 JSON/Markdown。
- 让 07-WP01～07-WP09 的每个任务都有明确落点、测试和证据输出。

**Non-Goals:**

- 不增加第四场景，不修改 `1.0.0` 场景的注入参数、Evidence、Root Cause Code 或发布阈值。
- 不向 Agent/Tool 增加 Docker、Ground Truth、execution、数据库管理或任意 Shell 权限。
- 不建立 `RcaGenerationSnapshot` 或第二套 JSON/Markdown 内容生成路径。
- 不执行或宣称阶段 08 的每场景 5 次、共 15 次质量发布聚合门禁。

## Decisions

### 1. 07-WP01：合同先行的独立 Python Fault Lab

`fault-lab/` 使用 Python 3.11+、锁定依赖和单一 CLI。ScenarioLoader/Validator 先按四类冻结 Schema 做 fail-closed 校验，再由显式 Registry 选择 EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector、TicketGenerator、GroundTruthGenerator、DatasetWriter；接口只交换带类型的计划、事实摘要和 Artifact 引用，不传递 Docker client 或任意命令字符串。

选择显式 Registry 而非动态 import/名称反射，是为了让场景能力可枚举、未知版本可拒绝、安全测试能证明没有隐藏注入器。Schema 正/负 fixture 和 Registry 装配报告落在 `outputs/phase7/07-WP01/`。

### 2. 07-WP02：持久 checkpoint 驱动的统一状态机

runner 固定状态为 `RESETTING → HEALTH_CHECKING → BASELINING → INJECTING → LOADING/COLLECTING → RECOVERING → EXPORTING → VALIDATING → COMPLETED|FAILED`。负载覆盖完整 240 秒，因此注入和采集是受时间窗约束的协作步骤，而不是互相嵌套的第二套状态机。每次转换先落 checkpoint 和 UTC 时间线，再执行外部副作用，恢复动作按 datasetRunId 标记并保持幂等。

顶层执行以 `try/finally` 强制进入恢复；原始异常与恢复异常分别保存为 primary/recovery failure。恢复核验覆盖 toxic、受控长事务、容器身份、服务 readiness、Prometheus target 和数据保留。只有 Fault Lab 进程挂载受限 Docker socket/代理，其他进程网络和身份层同时拒绝。逐阶段失败、取消、重复 reset 和双错误矩阵落在 `outputs/phase7/07-WP02/`。

### 3. 07-WP03：不可变 manifest 与三个物理隔离目录

执行开始生成 UUID datasetRunId 和显式 seed；baseline/fault/recovery 窗口由 runner 的 UTC 单调时间线派生。manifest 记录 commit、规范化 Compose digest、镜像 digest、去 Secret 的模型配置 digest、Scenario version，以及每个 Artifact 的 URI、大小和 SHA-256。导出先写临时运行目录，完成哈希与交叉引用校验后再原子发布为只读数据集。

`input/` 是 Agent 唯一只读挂载；`ground-truth/` 使用 Fault Lab 写、Evaluation 读的独立卷/凭证；`execution/` 只归 Fault Lab，路径本身不进入 Agent 可见 manifest、日志、Prompt 或 API。数据库以独立角色和 Schema/表授权隔离，Compose 明确拒绝 Agent 对受限卷和 Docker socket 的访问。验证报告落在 `outputs/phase7/07-WP03/`。

### 4. 07-WP04～07-WP06：三个场景只提供参数化 Adapter

三个场景共享 runner、load timeline、collector 和 validator，仅注入器与冻结 predicate 不同：

- 07-WP04 使用 Toxiproxy Adapter，锁定 order→inventory 3000ms±100ms、120 秒 toxic 和 240 秒负载；validator 同时验证请求统计、span 父子拓扑/窗口、inventory 资源未饱和及移除 toxic 后恢复。
- 07-WP05 使用受控长事务 Adapter，锁定 pool size 4、4 个事务、持有 90 秒、2 秒 connection timeout、120 秒故障窗和 240 秒负载；Adapter 只能连接 Sample 的受限测试入口，策略显式禁止停止共享 PostgreSQL。
- 07-WP06 使用外部容器控制 Adapter，记录原 inventory 容器/配置/卷 identity，停止 90 秒并恢复同一服务；validator 同时检查 readiness、连接失败/503、Prometheus target、业务恢复和卷数据不变。

场景 YAML 和 Ground Truth 示例继续以 `docs/design/contracts/examples/` 为冻结输入，实现 fixture 位于 Fault Lab 测试资源。每个场景输出参数实测、三窗口断言、required/one-of/forbidden Evidence、恢复与重复运行报告到对应 `outputs/phase7/07-WP0x/`。

### 5. 07-WP07：规则编译后的 Evidence Code 与 Ground Truth

Evidence rule 在场景加载时编译成对 Source type、Resource identity、service、window 和结构化 predicate 的合取判断；不读取自然语言摘要进行模糊匹配。同一 Evidence 最多产生一个 `source.type.fact` code，同一 code 的多个采样 Artifact 在 Evaluation 边界去重。规则冲突、无法唯一归码和窗口边界不明确均 fail closed。

GroundTruthGenerator 只读取冻结定义、injector 的实际执行事实、恢复事实和哈希有效的 Collector 结果；它不依赖 Agent/RCA，也不拥有模型客户端。生成结果通过 Schema、datasetRunId/Scenario version、Evidence/Tool/Root Cause 集合和恢复条件校验后写为只读并记录 digest。负向规则覆盖落在 `outputs/phase7/07-WP07/`。

### 6. 07-WP08：封账查询、事务外生成和单对象渲染

复用阶段 06 的 analysis seal：只有 `GENERATING_REPORT` 且具备稳定 `runVersion/analysisSealedAt` 的 Run 可进入 RCA。sealed-run query service 在短 `REPEATABLE READ READ ONLY` 事务中一次性查询当前 Run 的全部 Evidence、Hypothesis、支持/冲突关系、Verification、missingEvidence 和 Artifact 元数据，计算输入 digest 后结束事务；模型调用、Schema 校验和 renderer 均在事务外，不新增快照表。

RCA mapper 形成唯一领域对象，允许多个 Hypothesis、受限结论与 `INCONCLUSIVE/rootCause=null`。CitationValidator 在发布前验证 Run 归属、访问权、Artifact 哈希、incident window 重叠和 evidenceCode 一致性。RCA metadata 以 `(run_id, run_version)` 唯一绑定对象 digest 与 JSON/Markdown Artifact；两个 renderer 只读同一对象。若需新 Evidence，创建新 Run；同一封账版本失败重试继续使用相同输入 digest。验证证据落在 `outputs/phase7/07-WP08/`。

### 7. 07-WP09：独立 Java Evaluation 进程和透明指标结果

阶段 00 已建立 `opspilot-evaluation` 占位 Maven 模块；本阶段将其扩展为独立进程，只使用 Ground Truth 只读角色、RCA/Evidence/Tool/状态只读 Port、Artifact writer 和 evaluation result writer。它不暴露给 Agent 的网络路由，且不得复用 Supervisor 数据库角色。八个 metric calculator 各自产生规范化输入、去重集合、分子/分母、结果和 hard-gate facts；InvestigationEfficiency 保留多个维度，不压缩成单分数。

结果以 `(run_id, evaluation_profile_id, profile_version)` 唯一去重；场景内先聚合，再按三个场景等权 macro average。阶段 07 只验证三个场景各一个真实 Agent Run 的可计算与可复算，不设置发布通过声明。JSON/Markdown 从同一 EvaluationResult 渲染，Artifact 和 `opspilot.evaluation_result` 在幂等写回中关联。黄金样例、权限矩阵与复算报告落在 `outputs/phase7/07-WP09/`。

### 8. 工作包依赖和门禁顺序

07-WP01 完成合同与组件装配后，07-WP02 才能建立 runner；07-WP03 依赖二者提供时间线和事实。07-WP04～07-WP06 只在前三包门禁通过后并行接入共享 runner。07-WP07 等三个场景的执行事实和 Evidence 结构稳定后再冻结规则实现。07-WP08 依赖阶段 06 封账链与 07-WP07 code，07-WP09 最后消费 Ground Truth 和 RCA。

每个工作包先通过 Schema/单元与负向安全，再进入真实集成；最终 E2E 顺序为 Fault Lab → 数据集 input → 产品 API/真实 Agent → 封账 RCA → 独立 Evaluation。任何 Fake Provider、进程内 A2A、伪造 Artifact、关键词结果或未恢复环境都不能作为正常链路证据。

## Risks / Trade-offs

- [240 秒真实负载使测试慢且易受机器抖动影响] → 将冻结时长保留在真实场景门禁，单元测试使用虚拟时钟；报告记录主机资源与原始时间线，不放宽 predicate。
- [Docker socket 权限过大] → 仅 Fault Lab 身份可访问受限代理/白名单操作，并以容器、卷和场景标签做二次授权；Agent 网络与挂载层无路径。
- [Compose/image digest 受非语义字段影响] → 对配置做稳定规范化后计算 digest，同时保留原始 Artifact 哈希供审计。
- [遥测延迟可能跨越窗口边界] → Collector 保留事件时间和采集时间，规则只按冻结 event-time/window 语义匹配，边界 fixture 明确包含/排除规则。
- [RCA 查询期间并发迟到 Artifact] → analysis seal 写保护加 `runVersion`，短只读一致性事务计算输入 digest；迟到 Artifact 只记协议审计，不进入封账事实。
- [Evaluation 权限隔离增加部署复杂度] → 使用单独进程、数据库角色、卷和无 Agent 路由的网络策略，并把越权探测纳入必过门禁。
- [阶段 07 样本不足以代表发布质量] → 报告明确标记为功能/可复算验证，15 Run 阈值只在阶段 08 执行。

## Migration Plan

1. 先合入 Fault Lab 工程和只读合同加载，不授予 Docker 控制权，运行四类 Schema 与 Registry 门禁。
2. 增加 runner/checkpoint、数据集目录和独立卷/角色；通过恢复与隔离负向测试后再启用受控 Docker 操作。
3. 依次启用三个冻结场景，每个场景独立通过基线、故障、恢复和数据集校验后才允许进入 Agent E2E。
4. 增加 Evidence Code/Ground Truth 规则并冻结输出 digest；随后扩展 RCA migration/query/renderer，确认既有阶段 06 Run 仍可读取且未封账 Run 被拒绝。
5. 扩展 `opspilot-evaluation` 占位模块，增加角色、migration 和进程，完成黄金指标与真实 Run 复算，最后接入三场景正常链路。
6. 回滚时先停止 Evaluation 和 Fault Lab 调度、执行统一恢复并核验无 toxic/长事务/停机容器，再回退进程与代码；已发布数据集、RCA/Evaluation Artifact 和 append-only 结果保留用于审计，新增表不做破坏性 down migration。
