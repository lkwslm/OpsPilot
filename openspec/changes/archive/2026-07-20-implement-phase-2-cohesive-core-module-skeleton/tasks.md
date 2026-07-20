## 1. 02-WP01：物理模块与依赖护栏

- [x] 1.1 **02-WP01.T01** 补齐设计冻结的模块目录、父子 POM 和依赖管理，使 `opspilot-adapters` 只作聚合目录；执行 reactor 模块清单检查与根 Maven `verify`，证明没有单接口模块或 adapters 大模块。
- [x] 1.2 **02-WP01.T02** 在 `opspilot-core` 建立 `domain/application/port/policy` 包，在 `opspilot-a2a` 建立 `contract/client/server` 包级边界；添加包结构测试证明责任代码未越界。
- [x] 1.3 **02-WP01.T03** 建立 ArchUnit 或等价规则，禁止 core 导入 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 和厂商 SDK；逐类加入非法依赖样例并证明规则失败。
- [x] 1.4 **02-WP01.T04** 固定 Adapter 实现间、Adapter 到 Server、专业 Agent 到 Supervisor Repository 的禁止依赖，并限定 `opspilot-server` 为唯一 composition root；为每类反向依赖添加负向架构测试。
- [x] 1.5 **02-WP01.T05** 增加冻结模块清单测试或构建审查规则，明确“单个接口不得新增模块”；从正常骨架执行根 Maven `verify` 并保存模块清单结果。

## 2. 02-WP02：冻结身份、值对象与错误合同

- [x] 2.1 **02-WP02.T01** 为 Incident、Run、step、A2A Task、Evidence、Artifact 和 Hypothesis 建立强类型身份；除单调递增 `attempt` 外，对外身份统一为字符串 UUID，并以序列化测试证明 API 不暴露数据库自增键。
- [x] 2.2 **02-WP02.T02** 实现 `(runId, stepId, attempt)`、`(remoteAgentId, a2aTaskId)` 等组合身份和值相等规则，固定 `stepId` 在线路/Java/PostgreSQL 的字符串 UUID、`UUID`、`uuid` 表达；拒绝标题、数组序号和自增键作为身份。
- [x] 2.3 **02-WP02.T03** 实现 `rootCauseCode` 小写点分格式、已知目录校验和可空语义；测试未知或无法收敛根因保持 `null` 且不生成临时代码。
- [x] 2.4 **02-WP02.T04** 建立 `ChainFailure`、稳定错误分类、`retryable`、关联 ID、checkpoint 和受控日志引用类型；用敏感 cause fixture 证明只保留脱敏摘要。
- [x] 2.5 **02-WP02.T05** 为 UUID、哈希、`rootCauseCode`、跨 Run 引用和未知枚举编写边界/负向测试，证明非法身份在进入 Repository 或状态机前被拒绝且不改变状态。

## 3. 02-WP03：四层状态模型与权威迁移表

- [x] 3.1 **02-WP03.T01** 分别定义 Agent endpoint、A2A Task、本地 step attempt 和 Incident Run 状态类型及所有者；添加编译/架构测试禁止跨层复用通用 `status`。
- [x] 3.2 **02-WP03.T02** 将四张权威迁移表编码为显式 allowlist/Policy，区分同状态进度事件并禁止终态后继；参数化覆盖全部允许边和所有状态对的非法边。
- [x] 3.3 **02-WP03.T03** 实现 `INPUT_REQUIRED → WAITING_INPUT`、`AUTH_REQUIRED → WAITING_AUTH`、`COMPLETED → VALIDATING_RESULT` 映射；测试 Artifact 未通过媒体类型、Schema、哈希、身份或权限校验时 step 不完成。
- [x] 3.4 **02-WP03.T04** 实现 attempt 的 `DISPATCHING/RECONCILING/RETRY_SCHEDULED/CANCEL_REQUESTED` 语义；测试重试创建新 attempt 且旧终态、版本和审计不被覆盖。
- [x] 3.5 **02-WP03.T05** 实现 endpoint 的 `UNKNOWN/PROBING/READY/UNAVAILABLE/DRAINING/DISABLED`、`reasonCode`、`retryable`、Card digest 和探针时间；证明端点变化不改写 Task、单 Task 失败不直接改变 endpoint。
- [x] 3.6 **02-WP03.T06** 实现 Incident `WAITING_INPUT/WAITING_APPROVAL/CANCELLING/GENERATING_REPORT` 的严格分离及顶层 Task/Run 终态映射；非法迁移统一返回 `INVALID_STATE_TRANSITION` 且快照版本不变。

## 4. 02-WP04：IncidentAgentState 合同与迁移

- [x] 4.1 **02-WP04.T01** 按权威字段分组实现版本化 `IncidentAgentState` 的身份、进度、steps/attempts、Evidence/Hypothesis 引用、预算、循环治理、缺失、失败、报告和取消字段；为每个受支持版本添加 round-trip 测试。
- [x] 4.2 **02-WP04.T02** 为字符串、列表、steps、指纹、warnings 和序列化大小实现来自预算/Profile 的上限；测试越界更新 fail closed 且原快照不变。
- [x] 4.3 **02-WP04.T03** 实现只接受声明 source/target 版本的显式迁移链；测试未知版本返回 `STATE_SCHEMA_UNSUPPORTED`，不猜测、不丢字段且不继续 Run。
- [x] 4.4 **02-WP04.T04** 实现加载时引用验证，检查必需引用存在、属于同一 Run 且类型匹配；测试缺失、跨 Run 或类型错误返回 `STATE_REFERENCE_INVALID` 并停止 Run。
- [x] 4.5 **02-WP04.T05** 建立快照禁止内容扫描和负向测试，覆盖日志/Trace/代码/知识正文、完整 Evidence、Prompt/Response、隐藏推理、Message history、Tool 原始输出、Secret、Ground Truth 和大 Artifact。
- [x] 4.6 **02-WP04.T06** 为 `IncidentAgentState`、AgentScope state 和 A2A Task state 定义独立 Port/所有者；用恢复和并发测试证明三者不能互相覆盖 JSON 或版本。

## 5. 02-WP05：Application Use Case 与有界调度策略

- [x] 5.1 **02-WP05.T01** 定义创建、启动、恢复、取消 Incident Run、接收 A2A 结果和生成报告的 Use Case 输入输出；添加架构测试证明合同不含 Controller DTO 或框架/SDK 类型。
- [x] 5.2 **02-WP05.T02** 实现有限计划、串行 step、step 先 checkpoint 再委派、补证回环和 `WAITING_INPUT` 恢复后重新规划规则；注入 checkpoint 失败并证明无下游调用。
- [x] 5.3 **02-WP05.T03** 实现轮数、Agent 调用、Tool、A2A、Token、费用和 deadline 预算；测试模型输出不能提高、重置或绕过预算。
- [x] 5.4 **02-WP05.T04** 实现动作/补证指纹、Evidence novelty、连续无进展和停止优先级；测试预算耗尽与 `NO_PROGRESS` 停止新调用并进入受限报告。
- [x] 5.5 **02-WP05.T05** 实现取消意图 CAS、禁止新 step、下游取消收敛和迟到 Artifact 仅审计语义；覆盖取消/完成竞争和恢复后终态不复活。
- [x] 5.6 **02-WP05.T06** 只定义 AgentScope Adapter 所需事件/中断 Port 和可单步评估策略；以架构测试禁止 core 出现第二套业务 Agent `while` loop，并用测试 Port 完成正常、空知识、输入中断、预算耗尽、`NO_PROGRESS`、关键失败和取消恢复用例。

## 6. 02-WP06：窄 Port 与唯一 Evidence 边界

- [x] 6.1 **02-WP06.T01** 分离 Chat、Embedding、Rerank 三个 Provider Port，定义 deadline、identity、Usage 和错误结果；合同测试证明接口与编译依赖不暴露厂商协议对象。
- [x] 6.2 **02-WP06.T02** 定义 Tool、ObservabilitySource、CodeSource、Repository/UnitOfWork、Artifact、A2A Client、CodeAnalysis、SandboxRunner 窄接口，固定 `CodeSourceAdapter → CodeSnapshot → CodeAnalysisPort → CodeFinding`；用 GitHub/GitLab 测试 Adapter 和受限 Analyzer 证明凭证/API/宿主机路径不会越界。
- [x] 6.3 **02-WP06.T03** 为 Runtime Observation、CodeFinding 和 KnowledgeResult 实现唯一 `EvidenceNormalizer` 入口与统一 `provenanceRefs`；添加三类输入到不可变 Evidence 的共享合同测试。
- [x] 6.4 **02-WP06.T04** 强制 Diagnosis、Hypothesis 和 RCA 只接受 Evidence ID；添加编译边界与运行时 Schema 负向测试，拒绝 ObservationBatch、CodeFinding、KnowledgeResult 和厂商 DTO。
- [x] 6.5 **02-WP06.T05** 定义 Tool `SUCCEEDED/EMPTY/DENIED/FAILED`、受控摘要、Artifact/Evidence 引用和稳定 `errorCode`；测试大正文被外置为 Artifact 而不进入返回 DTO。
- [x] 6.6 **02-WP06.T06** 为五类扩展点定义稳定 descriptor/capability 接口；以架构测试证明只允许 composition root 显式装配，不实现通用 Registry、动态发现或 classpath 扫描。

## 7. 02-WP07：固定安全中间件链

- [x] 7.1 **02-WP07.T01** 实现固定 `Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit` 组合；用顺序测试证明步骤不可移除、绕过或重排。
- [x] 7.2 **02-WP07.T02** 实现短路语义，使校验、授权、审批、预算或 deadline 失败时 Port 执行次数为零，Normalize/结果校验失败不标记成功；覆盖拒绝、异常和重复调用。
- [x] 7.3 **02-WP07.T03** 实现主体、Agent、Tool 和上下文权限交集及 `READ_ONLY/CONTROLLED_EXECUTION/HIGH_RISK` policy；测试 MVP 中 `HIGH_RISK` 即使已审批也始终拒绝。
- [x] 7.4 **02-WP07.T04** 在成功、拒绝、失败和取消路径记录主体、动作指纹、权限、审批、预算、摘要和稳定错误；运行泄漏测试证明 Audit 不含 Secret、Prompt 或原始正文。
- [x] 7.5 **02-WP07.T05** 隔离 tracing/metrics observer 自身失败并记录错误，对所有安全中间件异常 fail closed；分别注入 observer 与安全步骤异常验证业务结果和 Port 调用次数。

## 8. 02-WP08：UnitOfWork、checkpoint 与事务 outbox

- [x] 8.1 **02-WP08.T01** 定义一次 checkpoint 原子包含状态/乐观锁版本、Tool/Model/A2A 审计、Evidence/Hypothesis/Artifact 绑定和 outbox event；逐个故障点测试无部分可见。
- [x] 8.2 **02-WP08.T02** 定义并实现 CAS 冲突后的重读、重新判定迁移和禁止覆盖写语义；覆盖并发取消/完成、重试和终态竞争。
- [x] 8.3 **02-WP08.T03** 区分 Domain Event 事实与命令，使事件只在提交后投影且消费者按 `eventId` 幂等；测试重复投递不重复投影或产生副作用。
- [x] 8.4 **02-WP08.T04** 在测试源码或 test fixture 实现支持提交、回滚、故障点和重复消费的内存 UnitOfWork；添加生产装配扫描证明其未注册为部署 Provider 或回退。
- [x] 8.5 **02-WP08.T05** 验证事务回滚不留下状态、审计、绑定或事件，且提交后 projector 失败只保留可重试投影错误、不回滚核心状态。

## 9. 02-WP09：核心验收套件与边界说明

- [x] 9.1 **02-WP09.T01** 按状态、快照、调度、Evidence、Middleware 和事务六组生成独立测试报告；汇总逻辑必须在任一组失败或缺失时阻止阶段门禁。
- [x] 9.2 **02-WP09.T02** 使用测试 Port 完成最小恢复链及正常、空知识、输入中断、预算耗尽、`NO_PROGRESS`、关键失败和取消路径，证明无需 Spring 容器、数据库、AgentScope 或 A2A HTTP。
- [x] 9.3 **02-WP09.T03** 扫描 core 依赖树和字节码导入，保存无 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 或厂商依赖的报告；注入传递禁用依赖并证明扫描失败。
- [x] 9.4 **02-WP09.T04** 输出 composition root、Repository 所有权、事务边界和扩展点清单，明确测试实现不得在生产 Profile 注册；从干净环境执行根构建和 core 专项构建，使报告绑定 commit、命令、退出码和 SHA-256，并验证替换测试 Port 时 core 生产代码零差异。
