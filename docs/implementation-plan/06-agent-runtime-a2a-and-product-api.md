# 阶段 06：AgentScope Runtime、六个 Agent、A2A 与产品 API

## 目标

实现统一 AgentScope 执行服务、六个受版本控制的 AgentProfile、九个 Tool、A2A 1.0 Client/Server/Task Store、Supervisor 编排，以及符合 OpenAPI 的 REST/SSE 交付层；官方 release/SDK 或官方 proto 生成对象锁定为 `v1.0.1`。

## 前置门禁

- core 状态机/Port、安全链和 outbox 已稳定；真实 Source、Provider、RAG 和持久化可用。
- 使用 Phase 0 验证过的 AgentScope/A2A API，不绕过 Adapter 或网络边界。

## 建设范围

1. 实现一个 `AgentExecutionService` 和 AgentScope Adapter；六角色共享框架内置 `ReActAgent` loop，BoundedReAct 只通过配置/Middleware/事件/中断施加轮数、预算、deadline、取消、checkpoint、重复指纹和 NO_PROGRESS。
2. 按 `agent-profile.schema.json` 实现 Supervisor、EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation Profile；冻结 role/skill、Prompt、Tool/A2A 白名单、Schema、预算、完成条件、沙箱和安全能力闭包。有效权限严格取“平台安全基线 ∩ 服务身份权限 ∩ AgentProfile ∩ 单次任务约束 ∩ 人工审批”；Profile 必须设置 `factsFromEvidenceOnly=true`，只保存逻辑 `modelProfileRef`，不得携带 Provider URL/API Key，也不能放宽 Ground Truth、Secret、任意命令、代码修改或平台安全基线。
3. 补齐九个 Tool：日志、指标、Trace、健康、拓扑、配置、代码、知识库、沙箱测试，并实现显式装配后冻结的 `ToolRegistry`；Tool 返回摘要和 Evidence/Artifact 引用，状态只为 `SUCCEEDED/EMPTY/DENIED/FAILED`。
4. 实现 GitHub/GitLab `CodeSourceAdapter`、专用 `CodeSourceAdapterRegistry`、Java Code Analyzer 与 Maven Sandbox Adapter。代码分析以“已配置且可只读访问的 GitHub/GitLab 仓库”为 MVP 能力前提，通过部署资源/image digest 解析 `repositoryId + 完整 commit SHA`，统一物化为不可变 CodeSnapshot 后才交给 Analyzer；禁止以当前 `main`、Agent 本地目录或模型提供的 URL 代替线上 revision。CodeFinding 必须经 `normalizeCode` 成为 Evidence，Sandbox 只允许审批后的白名单测试，HIGH_RISK/任意 Shell/代码写入始终禁止。
5. 实现 A2A 1.0 HTTP+JSON 的 Card、Message、Task、Artifact、`send/stream/get/cancel/subscribe`、版本/媒体类型/身份/skill/required extension 校验、messageId 幂等和终态规则；协议请求使用 `Content-Type: application/a2a+json`，Card `protocolVersion` 与 `A2A-Version` 固定为 `1.0`，实现依赖锁定到已验证的官方 `v1.0.1`。覆盖全部 TaskState，`UNSPECIFIED` 必须作为 `InvalidAgentResponse` 拒绝且不得持久化为有效状态；可靠最终结果只能使用完整 Artifact，状态 Message 只用于进度、澄清和限制说明。
6. 按冻结拓扑运行 8080 Supervisor/产品服务和 8081–8085 五专业服务；各进程使用独立身份、不同且按 skill 限权的 Token、A2A base URL 和数据库角色。产品端口只绑定宿主 `127.0.0.1:8080`，专业端口只在 Compose 内网，不内嵌进 `opspilot-server`；每个服务在自己的 origin 暴露唯一 `/.well-known/agent-card.json`。
7. 实现只读、进入镜像/部署清单并计算摘要的 Agent Directory，以及 AgentEndpointState 探针、Card digest、capability snapshot 和只选择 READY endpoint；模型不能动态发现 URL。按第 16.1 节顺序完成启动校验，并提供 liveness、readiness、models、capabilities 健康端点；capability 只报告 `UP|DOWN`，不提供 `DEGRADED` 运行模式。只有数据库/状态持久化、LLM、Embedding、Rerank、Supervisor 与全部专业 A2A skill 有效时 readiness 才为 UP，已完成真实模型探针后的 `KB_EMPTY` 只表示数据状态。
8. 实现 Supervisor 串行委派：先持久 step/messageId，再调用 A2A；接收 Artifact 时固定按“媒体类型 → major schema version → JSON Schema → Source/Resource/Task/Run 归属 → Artifact 哈希 → 引用权限 → 领域不变量”校验，全部通过后才单事务接收，失败不得部分写入；流断先 Get/Subscribe 对账；取消传播。进入 `GENERATING_REPORT` 前确认全部计划内 attempt 已终止或登记为 missingEvidence，并原子写入 `analysis_sealed_at/runVersion`；随后按 runId 直接查询数据库中的全部 Evidence/Hypothesis/关系/验证结果生成唯一结构化 RCA，再渲染 JSON/Markdown，不建立第二份 RCA 输入快照表。失败/拒绝后的重试必须创建新 attempt、新 messageId 和新 A2A Task，旧 attempt/Task 终态不可复活或覆盖。
9. 严格实现 A2A TaskState ↔ step 映射、step attempt、Incident Run 与 AgentScope session 隔离；Supervisor 使用 `sessionId="supervisor:" + runId`，专业 Agent 使用 `sessionId=serverAgentId + ":" + a2aTaskId`，MVP `userId` 固定为 `opspilot-system`，continuation 复用会话而新 attempt 不继承失败上下文。专业 Agent 不写 Supervisor 状态，恢复时以本地 binding + 远端 Task 对账。A2A `AUTH_REQUIRED` 只表示认证挑战，不能映射成业务 `WAITING_APPROVAL`；顶层 Supervisor Task 必须与 Run 的 `COMPLETED/FAILED/CANCELLED` 分别收敛到 `COMPLETED/FAILED/CANCELED`。
10. 实现北向 `/api` REST/SSE：完整实现 OpenAPI 中的所有操作；所有写请求要求 Idempotency-Key，作用域为 `principal + operation + key`，同 Key 同请求重放原响应、不同请求哈希返回冲突；统一 ErrorResponse/requestId、201/202/400/404/409 语义、runId 归属、报告未就绪 409、SSE 持久事件重放。
11. 实现错误关联：REST、SSE、A2A status、审计和日志共享 requestId/traceId/runId/stepId/a2aTaskId/invocationId 与受控 logArtifactId。

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 06-WP01 | AgentScope/Bounded ReAct、session、checkpoint 和中断规则 | 阶段 02 core policy，阶段 05 Provider |
| 06-WP02 | `agent-profile.schema.json`、六角色权限与能力闭包 | 06-WP01，阶段 04/05 能力快照 |
| 06-WP03 | Tool/Code Finding、CodeSource/CodeSnapshot、九 Tool 和 Sandbox 安全边界 | 06-WP02，阶段 03 Code Source 元数据，阶段 04 六 Tool，阶段 05 Knowledge |
| 06-WP04 | A2A v1.0.1 官方对象、A2A skill contract 和传输规则 | 阶段 01 A2A Spike |
| 06-WP05 | A2A/step/attempt/Run 状态矩阵与持久恢复规则 | 06-WP04，阶段 03 Task/lease 持久化 |
| 06-WP06 | 六进程冻结拓扑、Agent Directory/Card/健康合同 | 06-WP02、06-WP04、06-WP05 |
| 06-WP07 | Supervisor 委派、Artifact 接收顺序和 Evidence-only 不变量 | 06-WP03～06-WP06 |
| 06-WP08 | 启动顺序、Provider/A2A capability 与健康语义 | 06-WP06、06-WP07 |
| 06-WP09 | `opspilot-v1.yaml`、Incident Event Schema、幂等/SSE 合同 | 06-WP05、06-WP07 |
| 06-WP10 | 可靠性、安全、审计与关联标识规则 | 06-WP01～06-WP09 |

### 06-WP01：实现统一 AgentScope 执行内核

- **06-WP01.T1**：实现唯一 `AgentExecutionService` 和 AgentScope Adapter，封装官方 `ReActAgent` loop；业务层不得再实现第二套循环。
- **06-WP01.T2**：把轮数、Token/调用预算、deadline、取消、checkpoint、重复指纹和 `NO_PROGRESS` 作为 Middleware/事件/中断规则注入。
- **06-WP01.T3**：实现 `sessionId/userId` 规则、会话恢复和事件映射；中止后阻断新的 Tool/Model 调用。
- **目标文件**：Agent runtime Port/Adapter、middleware、session/checkpoint/event mapper。
- **验证与证据**：轮数/预算/取消/重复指纹测试、恢复测试和“无第二业务 loop”架构检查。

### 06-WP02：固化六个 AgentProfile 与能力闭包

- **06-WP02.T1**：按 Schema 创建 Supervisor 与五个专业 Agent 的 Profile/Prompt/输出 Schema，冻结 role、skill、白名单、预算和完成条件。
- **06-WP02.T2**：启动时计算“平台基线 ∩ 服务身份 ∩ Profile ∩ 单任务约束 ∩ 审批”的有效权限，缺 Tool/A2A skill 或越权配置时拒绝注册。
- **06-WP02.T3**：强制 `factsFromEvidenceOnly=true`；Profile 只引用逻辑模型 ID，不得含 URL、Key 或放宽 Ground Truth/Secret/命令/代码写入规则。
- **目标文件**：`agent-profile.schema.json`、六个 Profile/Prompt、Profile Registry/capability closure validator。
- **验证与证据**：Schema、权限交集、能力缺失、敏感字段和平台基线不可放宽测试。

### 06-WP03：完成九个 Tool、代码分析与沙箱

- **06-WP03.T1**：把阶段 04 的六个可观测 Tool 与代码、知识库、沙箱测试 Tool 装入显式冻结的 `ToolRegistry`。
- **06-WP03.T2**：实现 `GitHubCodeSourceAdapter`、`GitLabCodeSourceAdapter` 和冻结的 `CodeSourceAdapterRegistry`；两种平台响应统一为 `CodeSnapshot(snapshotId,sourceId,sourceKind,adapterId/version,repositoryId,commitSha,manifestArtifactId/hash,retrievedAt)`，平台 DTO、URL 和凭证不得进入 Tool/Agent/Analyzer。
- **06-WP03.T3**：Server/Supervisor 从 Release Manifest、镜像 label/digest 或部署元数据解析 `resourceId → imageDigest → repositoryId + 完整 commit SHA` 并投影到最小只读 `code_analysis_scope`；Code Agent 只按 A2A 的 `runId + repositoryId` 解析唯一 commit，无领域底表权限。一个 Source/Repository/commit 产生一个快照，多仓库分别分析并保留 provenance；零行或多个未消歧 commit 均返回 `CODE_REVISION_UNRESOLVED`，禁止自动分析 `main`、任意 branch、Agent 本地目录或把多个仓库拼成无来源根目录。
- **06-WP03.T4**：实现 Java Code Analyzer/Registry，只接收已校验 CodeSnapshot 的受限只读 workspace；`CodeFinding` 必须携带 repository/commit/root Artifact 与文件哈希，且只有经 `normalizeCode` 形成 Evidence 后才能供诊断使用。
- **06-WP03.T5**：实现代码源安全边界：host/repository allowlist、Secret `connectionRef`、归档/文件数量与大小限制、路径穿越、符号链接、子模块/LFS、文件类型、临时 workspace 隔离/销毁和禁止 Git hook/仓库脚本；稳定区分 `CODE_SOURCE_NOT_CONFIGURED`、`CODE_REVISION_UNRESOLVED` 与鉴权/超时 ChainFailure。
- **06-WP03.T6**：实现 Maven Sandbox/Registry，只执行审批后的白名单测试；拒绝 HIGH_RISK、任意 Shell、网络扩权和代码写入。
- **目标文件**：Tool Registry/Schema、Code Source Port/Adapters/Registry、deployment revision resolver、CodeSnapshot materializer/manifest、Code Analyzer Adapter、Sandbox Adapter、审批/权限 mapper。
- **验证与证据**：GitHub/GitLab 共享合同、相同 commit 的统一 CodeSnapshot、非 main 线上 commit、多仓库 provenance、revision 无法解析、任意 URL/路径、归档逃逸、九类 Tool 状态、Registry 冻结、代码 Evidence 和沙箱越权负向测试。

### 06-WP04：实现 A2A 1.0.1 协议合同

- **06-WP04.T1**：基于锁定的官方 `v1.0.1` release/SDK 或 proto 对象实现 Card、Message、Task、Artifact 和 HTTP+JSON Mapper。
- **06-WP04.T2**：实现 `send/stream/get/cancel/subscribe`，请求使用 `application/a2a+json`，Card `protocolVersion` 和 Header `A2A-Version` 固定为 `1.0`。
- **06-WP04.T3**：校验身份、skill、媒体类型、required extension、messageId 幂等和所有 TaskState；拒绝 `UNSPECIFIED` 与终态续写。
- **06-WP04.T4**：状态 Message 只表达进度/澄清/限制，可靠最终结果必须通过完整 Artifact 交付。
- **目标文件**：A2A contract/client/server mapper、官方对象锁定、JSON Schema/fixture。
- **验证与证据**：官方合同、媒体类型/版本、全状态、未知 extension、幂等和非法终态测试及 HTTP 抓包。

### 06-WP05：实现 A2A Task Store、attempt 与恢复

- **06-WP05.T1**：持久化本地 step/attempt 与远端 Task binding；调用前先保存 step/messageId，响应后用 CAS/事务推进。
- **06-WP05.T2**：重试创建新 attempt、新 messageId、新 A2A Task；旧终态不可复活、覆盖或继承失败上下文。
- **06-WP05.T3**：实现 stream 断连后的 Get/Subscribe 对账、专业进程/客户端重启恢复、取消传播和取消竞争处理。
- **06-WP05.T4**：严格映射 A2A TaskState、step 和 Run；隔离 `AUTH_REQUIRED` 与业务审批，保证顶层 Task/Run 终态收敛。
- **目标文件**：Task Store/Repository、binding/attempt service、reconciler、state mapper、cancel handler。
- **验证与证据**：重启、断流、CAS 冲突、重复响应、取消竞争、终态收敛的状态模型测试。

### 06-WP06：部署六进程与只读 Agent Directory

- **06-WP06.T1**：构建 8080 Supervisor/产品服务和 8081–8085 五个专业服务；每个服务使用独立身份、Token、数据库角色和最小 skill 权限。
- **06-WP06.T2**：产品端口只绑定 `127.0.0.1:8080`，专业端口只在 Compose 内网；每个 origin 暴露自己的 `/.well-known/agent-card.json`。
- **06-WP06.T3**：实现进入镜像/部署清单并计算 digest 的只读 Agent Directory，不允许运行时或模型动态发现 URL。
- **06-WP06.T4**：实现 endpoint probe、Card digest/capability snapshot 和只选 READY endpoint；按设计顺序执行启动校验。
- **目标文件**：六服务入口/镜像/Compose、身份与数据库授权、Agent Directory/endpoint state。
- **验证与证据**：端口暴露扫描、六 origin Card、身份隔离、Directory digest 和禁用进程内调用后的纵切。

### 06-WP07：实现 Supervisor 串行编排与 Artifact 接收

- **06-WP07.T1**：按权威状态机允许的条件顺序串行委派，支持显式跳过无需的代码分析和基于新 Evidence 的补证回环；模型不得让专业 Agent 相互发现或级联委派。
- **06-WP07.T2**：按“媒体类型 → major Schema → JSON Schema → Source/Resource/Task/Run 归属 → Artifact 哈希 → 引用权限 → 领域不变量”校验 Artifact。
- **06-WP07.T3**：全部校验通过后单事务接收 Artifact/Evidence；失败不得部分写入，Diagnosis/Hypothesis 只接收 Evidence ID。
- **06-WP07.T4**：进入 `GENERATING_REPORT` 前确认计划内 attempt 全部终止或形成 missingEvidence，原子递增 `runVersion` 并设置 `analysisSealedAt`；封账后拒绝当前 Run 的新 Evidence/Hypothesis/关系/验证写入。
- **06-WP07.T5**：报告服务按 runId 在短只读一致性事务中直接加载全部 Evidence、Hypothesis、支持/冲突关系、验证结果和 missingEvidence，事务外生成唯一结构化 RCA，再从同一对象渲染 JSON/Markdown；不得建立重复的 RCA 输入快照表或在模型调用期间持有数据库事务。
- **目标文件**：Supervisor orchestration、delegation policy、Artifact receiver、analysis seal/RCA query service、RCA handoff mapper。
- **验证与证据**：委派顺序、非法 Artifact 每一层负向用例、事务原子性、封账后写入拒绝、报告失败同数据重试、并发迟到 Artifact 和单一 RCA 对象测试。

### 06-WP08：实现服务健康、能力与就绪语义

- **06-WP08.T1**：每个进程提供 liveness、readiness、models、capabilities 端点；capability 只报告 `UP|DOWN`。
- **06-WP08.T2**：把数据库/状态持久化、LLM、Embedding、Rerank、Supervisor 和五个专业 skill 纳入 readiness；任一 required 能力无效即 DOWN。
- **06-WP08.T3**：外部临时不可达不应误报 liveness DOWN；真实模型探针完成后的 `KB_EMPTY` 只影响数据状态。
- **目标文件**：health contributors、probe scheduler、readiness aggregator、健康响应 Schema。
- **验证与证据**：逐依赖断开/恢复测试、liveness/readiness 分离和 capability snapshot 对账。

### 06-WP09：完整实现北向 REST/SSE 合同

- **06-WP09.T1**：逐项实现 OpenAPI 的 Incident/Run/Report/continuation/cancel/query 操作和 DTO 映射，不留未实现 operation。
- **06-WP09.T2**：所有写请求要求 `Idempotency-Key`；按 `principal + operation + key` 保存请求哈希/原响应，同 Key 异请求返回 409。
- **06-WP09.T3**：实现统一 ErrorResponse/requestId、201/202/400/404/409 语义、Run 归属和报告未就绪 409。
- **06-WP09.T4**：SSE 从持久事件表按 `Last-Event-ID` 重放，校验 principal/Run 归属并处理慢消费者/断连。
- **目标文件**：REST Controller/DTO、OpenAPI generated contract、idempotency service、SSE publisher/replay repository。
- **验证与证据**：OpenAPI 全 operation 合同、幂等重放/冲突、跨 Run/越权、SSE 断线重连测试。

### 06-WP10：统一关联标识与跨边界审计

- **06-WP10.T1**：在 REST、SSE、A2A、Tool、Provider、审计和日志中传递受控的 requestId/traceId/runId/stepId/a2aTaskId/invocationId。
- **06-WP10.T2**：大日志只记录受权 `logArtifactId`，错误响应与 A2A status 不泄漏密钥、内部堆栈或跨租户标识。
- **06-WP10.T3**：建立从北向请求到 A2A Task、Tool invocation、Evidence、RCA 的审计查询路径。
- **目标文件**：correlation middleware、MDC/telemetry mapper、audit events、error sanitizer。
- **验证与证据**：单次纵切关联图、脱敏扫描、跨租户负向测试和审计链完整性断言。

## 阶段内执行顺序

1. 先完成 06-WP01～06-WP03，冻结 Agent 执行、Profile 和 Tool 能力边界。
2. 06-WP04、06-WP05 完成协议与持久恢复后，再由 06-WP06 建立真实六进程网络边界。
3. 06-WP07 在 A2A/Directory 稳定后接入；06-WP08、06-WP09、06-WP10 收敛产品交付和运维合同。

## 测试与证据矩阵

| 验证层 | 必测内容 | 阶段证据 |
| --- | --- | --- |
| Schema/合同 | 六 Profile、九 Tool、A2A 1.0.1、OpenAPI/SSE | Schema/合同测试报告 |
| 状态/恢复 | attempt、stream 对账、重启、CAS、取消、顶层终态 | 状态模型与故障恢复报告 |
| 拓扑/安全 | 六进程 HTTP 边界、独立身份/角色、端口、Code Source allowlist/只读 workspace、能力闭包、沙箱 | 抓包、端口扫描和负向测试 |
| 纵切 | REST → Supervisor → 专业 A2A → Evidence → RCA → SSE | 关联 ID 完整的真实运行证据 |
| 运维 | 四类健康端点、Directory/Card digest、required 能力失效 | 依赖断开/恢复与 readiness 对账 |

## 主要输出

- AgentScope runtime Adapter、六个 AgentProfile/Prompt/映射器；
- 九个 Tool、GitHub/GitLab Code Source Adapter、CodeSnapshot/部署 revision resolver、Java Code Analyzer、Maven Sandbox 和各专用 Registry；
- A2A contract/client/server、Agent Directory、Card、Task Store 和恢复逻辑；
- 六进程 Compose 配置与服务身份/数据库隔离；
- 产品 REST/SSE Controller、DTO 映射、幂等和统一错误；
- Agent/A2A/API 的合同、状态、恢复和安全测试。

## 完成门禁

- 六张 Card 的 `protocolVersion: "1.0"` 与 skill/媒体类型/streaming/安全声明通过官方 v1.0 Schema 和锁定的 v1.0.1 SDK/对象合同，所有 A2A 操作、全部 TaskState、`UNSPECIFIED`、未知 required extension 和非法终态续写测试通过。
- 抓包证明 Supervisor 到专业 Agent 只走 HTTP+JSON；禁用进程内调用后仍可完成纵切。
- 任一专业 Agent 重启、stream 中断、客户端重启、取消竞争、CAS 冲突后可对账恢复且不重复副作用。
- 重试新建 attempt/messageId/Task、认证与审批隔离、顶层 Task/Run 终态收敛均通过自动化状态测试。
- 六角色动作只能来自 Profile 白名单；中止后不再调用 Tool/Model；没有第二套业务 `while` loop。
- 六个 AgentProfile 均通过 Schema、Registry 能力闭包和权限交集测试，不携带密钥/Provider URL，也不能扩大平台安全基线。
- Agent Directory 摘要、六个独立 origin 的 Card 与四类健康端点通过合同测试；任一 required 能力失效时 readiness DOWN，liveness 不因临时外部不可达而伪报进程死亡。
- Diagnosis/Hypothesis 只收到 Evidence ID；CodeFinding/KnowledgeResult/Observation 直传会被拒绝。
- GitHub/GitLab 对同一 repository/commit 生成统一 CodeSnapshot；代码分析只使用线上完整 commit SHA，多仓库 provenance 可回溯，无法解析 revision 时显式失败且从不回退 `main` 或本地目录。
- 进入 `GENERATING_REPORT` 后当前 Run 分析数据封账；RCA 直接查询数据库中的全量 Evidence/Hypothesis/关系/验证结果，同一封账数据可重试且不存在第二份输入快照表。
- API/SSE 完整符合 OpenAPI 和事件 Schema；幂等冲突、跨 Run 查询、Last-Event-ID 越权均被拒绝。
- 知识 `KB_EMPTY/NO_MATCH` 可继续现场调查；LLM/A2A/Tool/Embedding/Rerank 技术失败按第 17.4 节结束或形成允许的 `missingEvidence`，绝不伪装完成。

## 明确不做

- 不开放专业 Agent 自由对话、任意级联委派或并行子 Agent。
- 不实现自动 Patch、Git 写入/分支切换/Worktree/PR、模型驱动的任意 Git/Shell 命令、生产动作或可视化审批前端；只保留平台固定 CodeSourceAdapter 对 allowlist 仓库精确 commit 的只读获取。

## 设计依据

- [A2A 多 Agent 架构](../design/opspilot-system-design/03-a2a-multi-agent-architecture.md)
- [可靠性、安全与可观测性](../design/opspilot-system-design/08-reliability-security-and-observability.md)
- [北向/A2A/Tool 合同](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
- [运行拓扑](../design/opspilot-system-design/13-runtime-topology-and-phase0-gates.md)
