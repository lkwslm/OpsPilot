## 1. 06-WP01：统一 AgentScope 执行内核

- [x] 1.1 `06-WP01.T1` 复跑阶段 00 AgentScope Spike 合同，核对锁定 Maven 坐标、License、`ReActAgent`、Tool calling、Middleware、session/checkpoint、事件与取消真实 API；把差异和阻塞结论写入 `outputs/phase6/06-WP01/agentscope-api-audit.md`。
- [x] 1.2 `06-WP01.T1` 在 `opspilot-core` 定义唯一 `AgentExecutionService` Port 及项目自有 ExecutionRequest/Decision/Result/Usage/checkpoint/event 合同，确保 core 不引用 AgentScope、Spring 或传输 DTO。
- [x] 1.3 `06-WP01.T1` 在 `opspilot-agent-runtime-agentscope` 实现 AgentScope Adapter 和 DTO mapper，只封装官方 `ReActAgent` loop，不实现第二套业务循环。
- [x] 1.4 `06-WP01.T2` 实现 round、模型/Tool/Token 预算、父 deadline、取消和调用前门禁 Middleware，并用可控时钟验证所有等待与调用受父 deadline 限制。
- [x] 1.5 `06-WP01.T2` 实现动作指纹、重复检测、连续无新 Evidence、`NO_PROGRESS` 和稳定 termination reason mapper，覆盖各停止条件独立触发及优先级测试。
- [x] 1.6 `06-WP01.T3` 实现 Supervisor/专业 Agent 的 `sessionId`、MVP `userId=opspilot-system`、continuation 复用和新 attempt 会话隔离规则。
- [x] 1.7 `06-WP01.T3` 接入 PostgreSQL `AgentStateStore` 的 checkpoint/Usage/事件保存与恢复，验证保存失败 fail closed、重启隔离恢复和中止后无新 Tool/Model 调用。
- [x] 1.8 `06-WP01.T3` 实现 AgentScope 事件到审计/领域事件的脱敏映射，证明不保存隐藏推理、Secret、完整 Prompt 或原始正文。
- [x] 1.9 `06-WP01.T1-T3` 添加 ArchUnit/依赖测试阻止 AgentScope 类型进入 core 和第二业务 loop，运行 `./mvnw -pl opspilot-core,opspilot-agent-runtime-agentscope -am test` 并输出 `outputs/phase6/06-WP01/runtime-contract-report.json`。

## 2. 06-WP02：六个 AgentProfile 与能力闭包

- [x] 2.1 `06-WP02.T1` 根据 `docs/design/contracts/schemas/agent-profile.schema.json` 创建 Supervisor、EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation 六个版本化 Profile fixture。
- [x] 2.2 `06-WP02.T1` 为六角色创建版本化 system/role Prompt、输入/输出 Schema 引用、完成条件与 result mapper，并建立 role/skill 唯一配对合同测试。
- [x] 2.3 `06-WP02.T1` 实现显式 `AgentProfileRegistry` 的注册、重复检测、版本选择和冻结，禁止 classpath 扫描、动态发现和冻结后修改。
- [x] 2.4 `06-WP02.T2` 实现“平台安全基线 ∩ 服务身份权限 ∩ Profile ∩ 单次任务约束 ∩ 人工审批”的有效权限计算器，输出去密钥决策摘要。
- [x] 2.5 `06-WP02.T2` 实现 Profile capability closure validator，校验 Model capability、Tool contract major、A2A skill、Schema、Sandbox runner、安全策略及服务身份 scope 均完整兼容。
- [x] 2.6 `06-WP02.T2` 把 Profile/Prompt/逻辑模型引用、能力闭包 digest 和任务约束保存进 Run/Task effective snapshot，测试 Profile 更新不污染已有运行。
- [x] 2.7 `06-WP02.T3` 强制 `factsFromEvidenceOnly=true` 和 Ground Truth/Secret/任意命令/代码写入不可放宽基线，拒绝 Provider URL、API Key 或未知敏感字段。
- [x] 2.8 `06-WP02.T3` 添加六角色越权 Tool/A2A、缺 capability、Schema major 不兼容、HIGH_RISK 即使审批仍拒绝的负向合同测试。
- [x] 2.9 `06-WP02.T1-T3` 运行 `./mvnw -pl opspilot-core,opspilot-agent-runtime-agentscope,opspilot-server -am test` 和 Profile secret scan，输出 `outputs/phase6/06-WP02/profile-capability-closure-report.json`。

## 3. 06-WP03：九个 Tool、代码分析与沙箱

- [x] 3.1 `06-WP03.T1` 在 `opspilot-tools-default` 定义日志、指标、Trace、健康、拓扑、配置、代码、知识库、沙箱测试九个 Tool 的稳定 ID、输入/输出 Schema、权限等级和 `SUCCEEDED|EMPTY|DENIED|FAILED` 结果合同。
- [x] 3.2 `06-WP03.T1` 实现显式 `ToolRegistry` composition root，把阶段 04 六 Tool、阶段 05 Knowledge Search 与代码/沙箱 Tool 装配后冻结，测试重复、缺失、版本不兼容和冻结后修改。
- [x] 3.3 `06-WP03.T2` 在 `opspilot-core` 定义窄 `CodeSourcePort`、`CodeSnapshot`、只读 workspace handle、`CodeSourceExecutionContext` 和专用 `CodeSourceAdapterRegistry` 合同。
- [x] 3.4 `06-WP03.T2` 实现 `GitHubCodeSourceAdapter` 与 `GitLabCodeSourceAdapter` 的分页、只读鉴权、稳定错误映射和平台 DTO 隔离，并建立共享合同 fixture。
- [x] 3.5 `06-WP03.T3` 实现 Release Manifest、image label/digest 与部署元数据的 revision resolver，产出 `resourceId → imageDigest → repositoryId + 完整 commit SHA` 和最小只读 `code_analysis_scope`。
- [x] 3.6 `06-WP03.T3` 实现零/多 commit 消歧失败、短 SHA、branch、`main`、模型 URL 和 Agent 本地目录拒绝，稳定返回 `CODE_REVISION_UNRESOLVED` 或 `CODE_SOURCE_NOT_CONFIGURED`。
- [x] 3.7 `06-WP03.T2-T3` 实现每 repository/commit 独立 CodeSnapshot 物化、manifest Artifact/SHA-256、文件哈希和多仓库 provenance，禁止把多个仓库拼成无来源根目录。
- [x] 3.8 `06-WP03.T5` 实现 host/repository allowlist、Secret `connectionRef`、归档/文件数量与大小、文件类型、子模块/LFS 和下载超时限制。
- [x] 3.9 `06-WP03.T5` 实现路径穿越、绝对路径、符号链接、Git hook/仓库脚本拒绝，以及临时只读 workspace 隔离、成功/失败/取消后的确定销毁。
- [x] 3.10 `06-WP03.T4` 在 `opspilot-adapters/code-java` 实现只消费已校验 CodeSnapshot 的 Java Analyzer/Registry，返回含 repository/commit/root Artifact、文件哈希、位置和 Analyzer 版本的 CodeFinding。
- [x] 3.11 `06-WP03.T4` 接入 `EvidenceNormalizer.normalizeCode` 与 EvidenceBundle mapper，测试未经规范化或 provenance/hash 不完整的 Finding 不能进入 Diagnosis/Hypothesis/RCA。
- [x] 3.12 `06-WP03.T6` 在 `opspilot-adapters/sandbox-maven` 实现 Maven Sandbox/Registry，只允许 Profile 与审批共同白名单的 test suite，并施加网络、CPU、内存、时长和写目录限制。
- [x] 3.13 `06-WP03.T6` 添加 HIGH_RISK、任意 Shell、源码写入、网络扩权、伪造 approval、超时和取消负向测试，证明拒绝路径不启动 Sandbox 进程。
- [x] 3.14 `06-WP03.T1-T6` 运行 `./mvnw -pl opspilot-tools-default,opspilot-adapters/code-java,opspilot-adapters/sandbox-maven -am test`，输出 GitHub/GitLab 共享合同、多仓库 provenance、归档逃逸、九 Tool 状态和 Sandbox 安全证据到 `outputs/phase6/06-WP03/`。

## 4. 06-WP04：A2A 1.0.1 协议合同

- [x] 4.1 `06-WP04.T1` 复核阶段 00 A2A Spike 的官方 `v1.0.1` release/SDK 或 proto 生成对象、License、HTTP+JSON 绑定和 `subscribe` API，并输出 `outputs/phase6/06-WP04/a2a-api-audit.md`；证据不一致时阻塞实现。
- [x] 4.2 `06-WP04.T1` 在 `opspilot-a2a` 实现项目合同与官方 Card、Message、Task、Artifact 对象的双向 mapper，框架/传输类型不进入 core。
- [x] 4.3 `06-WP04.T1` 创建六张 Card fixture 和服务生成器，固定 `protocolVersion: "1.0"`、Agent 身份、skill、媒体类型、streaming、安全方案与 extension 声明。
- [x] 4.4 `06-WP04.T2` 实现 A2A Server 的 `send/stream/get/cancel/subscribe` HTTP+JSON endpoint、持久响应读取和标准错误 mapper。
- [x] 4.5 `06-WP04.T2` 实现 A2A Client 的五类操作、stream/subscribe 生命周期和取消传播，固定 `application/a2a+json` 与 `A2A-Version: 1.0`。
- [x] 4.6 `06-WP04.T3` 实现服务身份、目标 Agent、skill scope、媒体类型、版本和 required extension 校验，未知 required extension 在创建 Task 前 fail closed。
- [x] 4.7 `06-WP04.T3` 实现 `messageId + request hash` 持久幂等、相同请求重放和异请求冲突，覆盖并发重复 send。
- [x] 4.8 `06-WP04.T3` 实现全部官方 TaskState mapper 与合法迁移，拒绝 `UNSPECIFIED`、未知枚举、终态续写和终态复活。
- [x] 4.9 `06-WP04.T4` 分离状态 Message 与可靠 Artifact，保证进度/澄清/限制消息不能冒充最终结果，完整 Artifact 携带 Schema/归属/hash/引用。
- [x] 4.10 `06-WP04.T1-T4` 运行 `./mvnw -pl opspilot-a2a -am test`、官方对象/Schema fixture 和真实 HTTP 抓包测试，输出 `outputs/phase6/06-WP04/a2a-contract-report.json` 与脱敏抓包。

## 5. 06-WP05：A2A Task Store、attempt 与恢复

- [x] 5.1 `06-WP05.T1` 设计并添加 step attempt、messageId/request hash、远端 Agent/Task binding、Task 状态、Artifact 游标和 CAS version 的 PostgreSQL migration/约束。
- [x] 5.2 `06-WP05.T1` 实现 A2A Task Store/Repository 和委派事务，在网络调用前保存 `(runId,stepId,attempt)` 与 messageId，响应后用 CAS 原子绑定远端 Task。
- [x] 5.3 `06-WP05.T2` 实现 retry/continuation service：重试创建新 attempt/messageId/Task/session，continuation 复用原 binding/session，旧终态保持不可变。
- [x] 5.4 `06-WP05.T2` 添加重复/迟到响应和 attempt 间污染测试，证明旧 Task 不能覆盖新 attempt 或重复已提交副作用。
- [x] 5.5 `06-WP05.T3` 实现 stream 断连后的 Get/Subscribe reconciler，从本地 binding、远端持久 Task/Artifact 和游标确定恢复动作。
- [x] 5.6 `06-WP05.T3` 实现 Supervisor Client、专业 Server 与 Agent runtime 分别重启后的扫描/领取/恢复，禁止把旧 attempt 直接重置为初始状态。
- [x] 5.7 `06-WP05.T3` 实现 Run/step 取消意图持久化、远端 cancel 传播、重复 cancel、完成/取消竞争和超时对账。
- [x] 5.8 `06-WP05.T4` 实现全部 A2A TaskState ↔ step attempt 矩阵，严格隔离 `AUTH_REQUIRED→WAITING_AUTH` 与业务 `WAITING_APPROVAL`。
- [x] 5.9 `06-WP05.T4` 实现顶层 Supervisor Task 与 Run `COMPLETED/FAILED/CANCELLED` 到 A2A `COMPLETED/FAILED/CANCELED` 的终态收敛和不变量断言。
- [x] 5.10 `06-WP05.T1-T4` 运行 `./mvnw -pl opspilot-core,opspilot-a2a,opspilot-adapters/persistence-postgres -am test`，覆盖断流、三类重启、CAS 冲突、幂等、取消竞争和终态收敛，输出 `outputs/phase6/06-WP05/recovery-state-matrix.json`。

## 6. 06-WP06：六进程与只读 Agent Directory

- [x] 6.1 `06-WP06.T1` 为 Supervisor/产品服务与五个专业服务建立独立启动入口、镜像 target、端口、Profile 装配和进程级配置，专业 Agent 不打包进 8080 运行入口。
- [x] 6.2 `06-WP06.T1` 创建六个服务身份、不同且按 skill 限权的 Token/Secret ref、A2A base URL 和最小 PostgreSQL 角色/GRANT migration。
- [x] 6.3 `06-WP06.T2` 更新 `deployment/docker-compose.yml`，只将产品端口绑定 `127.0.0.1:8080`，8081–8085 仅接入 Compose 内网。
- [x] 6.4 `06-WP06.T2` 在每个服务自身 origin 暴露唯一 `/.well-known/agent-card.json`，并验证 8080 不代理五个专业 Card。
- [x] 6.5 `06-WP06.T3` 定义 Agent Directory 机器合同和只读加载器，把 Directory 放入镜像/部署清单并计算配置版本与 SHA-256 digest。
- [x] 6.6 `06-WP06.T3` 禁止运行时/模型修改 Directory 或发现 URL，添加未知 endpoint、路径注入和启动后变更负向测试。
- [x] 6.7 `06-WP06.T4` 实现 `AgentEndpointState`、周期 endpoint/Card/skill/capability probe、Card digest 对账与只选择 READY endpoint 的 resolver。
- [x] 6.8 `06-WP06.T4` 实现固定启动序列和流量门禁，证明 capability/Registry/Card/Directory 校验完成前服务 liveness 可用但不接受新 Task。
- [x] 6.9 `06-WP06.T1-T4` 执行六进程 Compose 端口扫描、六 origin Card、Token 跨 skill、数据库越权和禁用进程内路径测试，输出 `outputs/phase6/06-WP06/topology-security-report.json` 与真实 HTTP 抓包。

## 7. 06-WP07：Supervisor 编排、Artifact 接收与 RCA

- [x] 7.1 `06-WP07.T1` 实现 Supervisor orchestration application service，按权威 Run/step 状态与计划顺序串行委派 EvidenceCollector、可选 CodeAnalysis、Knowledge、Diagnosis 和 Remediation。
- [x] 7.2 `06-WP07.T1` 实现代码分析显式跳过条件和基于新 Evidence 的有界补证策略，禁止专业 Agent 发现/调用同伴及无新 Evidence 的重复回环。
- [x] 7.3 `06-WP07.T1` 把目标 Agent/skill、输入 Evidence/Artifact 引用、剩余预算/deadline 和 capability snapshot 写入委派记录，事务提交前不得出网。
- [x] 7.4 `06-WP07.T2` 实现 Artifact receiver 的媒体类型、major schema version、JSON Schema 三层协议校验和短路错误 mapper。
- [x] 7.5 `06-WP07.T2` 实现 Source/Resource/Task/Run 归属、SHA-256、引用权限、领域不变量五层校验，并为八层各建立唯一失败 fixture。
- [x] 7.6 `06-WP07.T3` 实现 Artifact/Evidence/Hypothesis/关系/验证结果/outbox 单事务接收，确保任意校验或写入失败不产生部分状态。
- [x] 7.7 `06-WP07.T3` 强制 Diagnosis/Hypothesis 只接收当前 Run 的 Evidence ID，拒绝未规范化 Observation、CodeFinding、KnowledgeResult 和跨 Run 引用。
- [x] 7.8 `06-WP07.T4` 实现 `GENERATING_REPORT` 前的 attempt 终态/missingEvidence 对账，以及原子递增 `runVersion`、写 `analysisSealedAt` 和封账后 Repository 写入拒绝。
- [x] 7.9 `06-WP07.T4` 添加并发迟到 Artifact、stream 对账与 analysis seal 竞争测试，证明封账输入不变且协议审计保留迟到事件。
- [x] 7.10 `06-WP07.T5` 实现 `RcaReportService` 的短只读一致性查询，按 `runId + runVersion` 加载完整 Evidence/Hypothesis/关系/验证/missingEvidence 并在事务外调用模型。
- [x] 7.11 `06-WP07.T5` 实现唯一结构化 RCA 的 Schema/rootCauseCode/引用校验和同源 JSON/Markdown Artifact 渲染，禁止第二 RCA 输入快照表。
- [x] 7.12 `06-WP07.T1` 接入阶段 05 `KB_EMPTY/NO_MATCH` 与技术失败结果映射，验证知识空结果继续现场调查，而 LLM/A2A/Tool/Embedding/Rerank 失败只能按状态矩阵结束或登记具体 missingEvidence，绝不伪装完成。
- [x] 7.13 `06-WP07.T1-T5` 运行 `./mvnw -pl opspilot-core,opspilot-a2a,opspilot-server -am test`，输出委派顺序、八层负向、原子接收、封账与同数据报告重试证据到 `outputs/phase6/06-WP07/`。

## 8. 06-WP08：健康、能力与就绪语义

- [x] 8.1 `06-WP08.T1` 定义 liveness、readiness、models、capabilities 的稳定响应 Schema，在六个进程实现端点并统一 UTC、配置/Directory/Card digest 与脱敏错误字段。
- [x] 8.2 `06-WP08.T1` 实现 capability `UP|DOWN` 状态、probe 时间/版本和 Registry/endpoint snapshot 对账，禁止 `DEGRADED` 运行模式。
- [x] 8.3 `06-WP08.T2` 为 Supervisor/产品服务聚合数据库、Agent 状态存储、LLM、Embedding、Rerank、Supervisor runtime 与五个专业 A2A skill 的 required readiness。
- [x] 8.4 `06-WP08.T2` 为五个专业服务聚合各自数据库/状态、模型、Profile、Tool/skill、Directory/Card 的 required readiness，并让 DOWN endpoint 停止接收新 Task。
- [x] 8.5 `06-WP08.T3` 分离 liveness 与外部依赖故障，添加逐依赖断开/恢复和真实探针回升测试；外部超时不误报进程 liveness DOWN。
- [x] 8.6 `06-WP08.T3` 固化 `KB_EMPTY` 为知识数据状态，测试真实模型探针完成后空知识库不改变模型 capability 或跳过 required 探针。
- [x] 8.7 `06-WP08.T1-T3` 运行六进程健康共享合同和 capability snapshot/Directory 对账，输出 `outputs/phase6/06-WP08/health-readiness-matrix.json`。

## 9. 06-WP09：北向 REST/SSE 产品合同

- [x] 9.1 `06-WP09.T1` 从 `docs/design/contracts/openapi/opspilot-v1.yaml` 生成/校验服务接口与 DTO，建立 operationId 覆盖测试，禁止手写 DTO 漂移机器合同。
- [x] 9.2 `06-WP09.T1` 实现 `createIncident`、`getIncident`、`startIncidentRun`、`resumeIncidentRun` 和 `cancelIncidentRun` Controller/mapper 及领域用例接线。
- [x] 9.3 `06-WP09.T1` 实现 `getIncidentState`、`getIncidentReport`、`listToolCalls` 和 `decideApproval` Controller/mapper 及领域用例接线。
- [x] 9.4 `06-WP09.T2` 设计并添加 `principal + operation + key`、request hash、处理状态、原 status/headers/body 的 API idempotency migration 与 Repository。
- [x] 9.5 `06-WP09.T2` 实现所有写 operation 的 `Idempotency-Key` Middleware、并发占用、同请求原响应重放、异请求 409 和失败恢复。
- [x] 9.6 `06-WP09.T3` 实现统一 `ErrorResponse/requestId`、201/202/400/404/409 mapper、未知枚举/非法 UUID fail closed 和报告未就绪 409。
- [x] 9.7 `06-WP09.T3` 对所有 incidentId/runId/approvalId/tool call/report 引用实施 principal 与 Run 归属校验，覆盖跨 Run/跨主体且不泄露存在性的负向测试。
- [x] 9.8 `06-WP09.T4` 实现 `streamIncidentEvents`，从持久事件表按目标 Run 和 `Last-Event-ID` 读取事务提交后的稳定序列。
- [x] 9.9 `06-WP09.T4` 实现 SSE principal/Run 游标校验、跨 Run 拒绝、心跳、慢消费者边界、断连清理和 projector/outbox 重试隔离。
- [x] 9.10 `06-WP09.T1-T4` 运行 OpenAPI 全 operation 合同、幂等并发/重放/冲突、报告状态、跨 Run 授权及 SSE 断线重连测试，输出 `outputs/phase6/06-WP09/product-api-contract-report.json`。

## 10. 06-WP10：关联标识、审计与阶段门禁

- [x] 10.1 `06-WP10.T1` 定义关联上下文与所有权规则，在 REST/SSE、Agent runtime、A2A、Tool、Provider、Artifact 和 outbox 传播 `requestId/traceId/runId/stepId/a2aTaskId/invocationId`。
- [x] 10.2 `06-WP10.T1` 实现 HTTP header/A2A metadata/MDC/telemetry mapper，拒绝调用方覆盖权威 Run/Task/invocation 标识并验证异步恢复后关联连续。
- [x] 10.3 `06-WP10.T2` 统一 REST ErrorResponse、SSE error、A2A status、ChainFailure、审计和日志的稳定错误分类与脱敏，移除 Secret、Prompt、原始正文、内部堆栈和跨主体标识。
- [x] 10.4 `06-WP10.T2` 实现大日志 Artifact 化、`logArtifactId` 受控引用、SHA-256/访问级别/保留策略和未授权解析拒绝，线路上不内联大正文。
- [x] 10.5 `06-WP10.T3` 实现按 principal/Incident/Run 逐跳授权的审计查询，从北向请求关联到 A2A Task、Tool/Provider invocation、Artifact、Evidence、analysis seal 和 RCA。
- [x] 10.6 `06-WP10.T3` 建立成功、拒绝、失败、取消与 Artifact 八层校验失败的审计完整性测试，验证不存在孤立副作用或仅凭 traceId 越权查询。
- [x] 10.7 `06-WP10.T1-T3` 在真实六进程 Compose 中执行 `REST → Supervisor → 专业 A2A → Tool/Provider → Evidence → RCA → SSE` 纵切，生成 `outputs/phase6/06-WP10/correlation-graph.json`、抓包和端口证据。
- [x] 10.8 `06-WP10.T1-T3` 运行 `./mvnw verify`、OpenAPI/JSON Schema/A2A 官方合同、三类重启/断流/取消故障矩阵、`python scripts/security/scan_repository.py` 和 Compose smoke，汇总到 `outputs/phase6/phase6-gate-summary.json`；任一真实门禁缺失或失败时不得标记阶段完成。
