## 背景

阶段 02 至 05 已提供内聚 core、四层状态机、安全 Middleware、PostgreSQL/Artifact/SSE 持久原语、真实可观测 Tool、模型 Provider 与 RAG，但 `opspilot-agent-runtime-agentscope`、`opspilot-a2a` 和产品 API 仍未形成真实运行链。阶段 00 的 Spike 已验证 AgentScope 与 A2A 的最小 API，本阶段只能复用已验证的框架边界和官方 A2A `v1.0.1` 对象，不能把框架类型带入 core，也不能绕过 Adapter、持久化或 Compose 网络。

事实源按机器合同、设计状态矩阵/不变量、阶段计划的顺序解释。工作包依赖为：06-WP01 先形成统一运行内核；06-WP02 固化 Profile；06-WP03 在能力闭包上装配 Tool、代码源与沙箱；06-WP04/05 完成 A2A 合同、持久状态和恢复；06-WP06 建立真实六进程边界；06-WP07 接入 Supervisor；06-WP08/09/10 最后收敛健康、产品 API 与关联审计。

## 目标与非目标

**目标：**

- 让六个 Agent 共享一个可审计、可恢复且有界终止的 AgentScope 执行服务，并以版本化 Profile 收窄能力。
- 让九个 Tool、GitHub/GitLab Code Source、Java Analyzer 和 Maven Sandbox 只通过窄 Port、专用 Registry 与 Evidence 边界协作。
- 让 Supervisor 与五个专业 Agent 通过持久、幂等、可对账的 A2A 1.0 HTTP+JSON 跨进程通信。
- 让 Artifact 只有完成固定顺序校验并原子接收后才能影响权威领域状态，封账后从同一 Run 数据生成唯一 RCA。
- 完整交付 OpenAPI REST/SSE、服务健康、只读 Directory、身份/端口隔离与端到端关联审计。

**非目标：**

- 不实现阶段 07 的冻结故障场景与确定性评测编排。
- 不增加第二套 Agent loop、专业 Agent 之间的发现/级联委派、动态 URL/Tool 扫描或进程内 A2A 快捷路径。
- 不支持 HIGH_RISK、任意 Shell、代码修改、任意仓库/路径、`main`/branch 回退或跨安全基线扩权。
- 不增加动态模型路由、自动 failover、`DEGRADED` 能力模式或第二份 RCA 输入快照表。

## 设计决策

### 决策一：唯一执行服务包裹框架 loop

`AgentExecutionService` 是 core 面向 Agent 的唯一执行 Port，AgentScope Adapter 内部复用官方 `ReActAgent` loop。最大轮次、模型/Tool/Token 预算、deadline、外部取消、checkpoint、重复动作指纹和连续无新 Evidence 由 Middleware、框架事件和中断回调注入；业务模块不得编写第二个 `while` loop。每次动作前后都检查取消与剩余预算，checkpoint 保存失败立即 fail closed，中止事件后禁止新 Tool/Model 调用。

选择包装框架 loop 而非复制 BoundedReAct，是为了保留 AgentScope 的会话/工具能力并把 OpsPilot 的安全、预算和状态规则放在可测试边界上。框架 DTO 只存在于 Adapter，core 只看到项目自有 Decision、Result、Usage、checkpoint 和事件。

### 决策二：Profile 是只能收窄的平台策略

六个 Profile 以 `agent-profile.schema.json` 校验并由显式 `AgentProfileRegistry` 装配、完成启动校验后冻结。有效权限固定为“平台安全基线 ∩ 服务身份权限 ∩ Profile ∩ 单次任务约束 ∩ 有效人工审批”；任一集合缺失、Tool/A2A skill 未注册、Schema major 不兼容或配置试图放宽基线时拒绝注册。

Profile 只保存逻辑 `modelProfileRef`、版本化 Prompt/Schema、白名单、预算、完成条件与沙箱策略，强制 `factsFromEvidenceOnly=true`，禁止 Provider URL/API Key、Ground Truth、Secret、任意命令和代码写入。选择静态 Profile 和能力闭包而非运行时发现，是为了让 Card、Directory digest、审计和重放具有稳定含义。

### 决策三：Tool、代码源、Analyzer 与 Sandbox 分层收敛

九个产品 Tool 通过显式 `ToolRegistry` 注册后冻结，统一返回摘要、Evidence/Artifact 引用和 `SUCCEEDED|EMPTY|DENIED|FAILED`，不向 Agent 暴露厂商 DTO。可观测、代码、知识和沙箱分别复用既有 Source/Knowledge Port 或新的专用窄 Port，不创建万能 Extension Host。

部署 revision resolver 从 Release Manifest、image label/digest 或部署元数据解析 `resourceId → imageDigest → repositoryId + 完整 commit SHA`，并投影最小只读 `code_analysis_scope`。GitHub/GitLab Adapter 每次只物化一个不可变 `CodeSnapshot` 和受限只读 workspace；平台 URL、凭证和 DTO 不越过 Adapter。多仓库分别生成快照，Java Analyzer 只消费已校验快照，`CodeFinding` 只有经 `EvidenceNormalizer.normalizeCode` 后才成为事实。Maven Sandbox 只运行审批与 Profile 双重白名单的测试套件，禁网或 allowlist 网络、限制资源并销毁临时 workspace。

### 决策四：A2A 合同与领域状态分离

`opspilot-a2a` 使用锁定的官方 `v1.0.1` release/SDK 或 proto 生成对象实现 Mapper、Client、Server 与 JSON fixture；HTTP 请求固定 `Content-Type: application/a2a+json`、`A2A-Version: 1.0`，Card 固定 `protocolVersion: "1.0"`。边界依次校验服务身份、skill、媒体类型、版本、required extension 与对象 Schema。

A2A TaskState 不直接成为 Run 状态。`UNSPECIFIED` 以 `InvalidAgentResponse` 拒绝且不持久化；`AUTH_REQUIRED` 只映射本地 `WAITING_AUTH`，不映射业务 `WAITING_APPROVAL`；终态单调。状态 Message 只承载进度、澄清和限制，可靠最终结果必须是完成并校验的 Artifact。

### 决策五：先持久委派意图，再发网络请求

每次委派先在本地事务创建 `(runId,stepId,attempt)`、唯一 `messageId`、request hash 与待绑定状态，再调用远端 A2A。Server 以 `messageId + request hash` 幂等：同请求返回原 Task，异请求冲突。响应后用 CAS/事务写入 `(remoteAgentId,a2aTaskId)` binding 和状态，重复/迟到响应不得倒退终态或重复副作用。

失败、拒绝或重新规划后的重试必须创建新 attempt、messageId、A2A Task 与专业 Agent session；continuation 才复用原 Task/session。stream 断开、Client/Server 重启或取消竞争时，reconciler 先用本地 binding 调用 Get/Subscribe 对账，再决定续订、取消或收敛，禁止直接重置旧 attempt。

### 决策六：六进程与 Directory 固化信任边界

8080 同时承载 Supervisor A2A origin 和北向产品 API，仅绑定宿主 `127.0.0.1:8080`；8081–8085 分别承载 EvidenceCollector、CodeAnalysis、Knowledge、Diagnosis、Remediation，只暴露在 Compose 内网，不内嵌进 `opspilot-server`。每个进程使用独立服务身份、按 skill 限权 Token、A2A base URL 和最小数据库角色，并在自身 origin 暴露唯一 Card。

Agent Directory 是随镜像/部署清单发布的只读配置并计算 digest；模型和运行时不能新增或改写 URL。启动按“机器合同/Profile/Directory 静态校验 → 服务身份与 Secret ref → Provider/Tool/skill 能力探针 → Card/Directory digest 对账 → Registry 冻结 → readiness”推进。调度仅选择 `AgentEndpointState=READY` 且 Card/skill/capability snapshot 匹配的 endpoint。

### 决策七：Supervisor 串行编排且专业 Agent 不共享权威状态写权限

Supervisor 按权威 Run/step 状态机串行委派专业 Agent，可显式跳过无适用 revision 的代码分析，并只在新增 Evidence 能改变结论时发起有界补证回环。专业 Agent 不能发现或调用其他专业 Agent，也不能写 Supervisor 的 Incident/Run/step/Evidence 领域表；它只维护本地 A2A Task/AgentScope binding 并交付 Artifact。

Supervisor 接收 Artifact 固定执行“媒体类型 → major schema version → JSON Schema → Source/Resource/Task/Run 归属 → Artifact SHA-256 → 引用权限 → 领域不变量”。任何一步失败都不写领域事实；全部通过后在单事务保存 Artifact 引用、Evidence/Hypothesis/关系/验证结果与 outbox。Diagnosis/Hypothesis 只接受 Evidence ID，不接受未规范化 Observation、CodeFinding 或 KnowledgeResult。

### 决策八：analysis seal 冻结唯一 RCA 输入

进入 `GENERATING_REPORT` 的事务先确认所有计划内 attempt 已终止或登记 `missingEvidence`、已接收 Artifact 均完成原子写入，再递增 `runVersion` 并写入 `analysisSealedAt`。封账后 Repository 拒绝该 Run 的 Evidence、Hypothesis、关系和验证结果写入；迟到 Artifact 留在协议审计侧并以稳定冲突结束，补充事实必须创建新 Run。

`RcaReportService` 在短只读一致性事务中按 `runId + runVersion` 直接读取全量事实并构造一个结构化 RCA 输入，事务结束后调用模型、校验引用与 `rootCauseCode`，保存唯一结构化 RCA，再从同一对象渲染 JSON/Markdown Artifact。选择直接查询封账数据而非复制快照表，避免形成第二份事实源和双写一致性问题。

### 决策九：健康、产品 API 与 SSE 共用持久事实

每个进程提供 liveness、readiness、models、capabilities；capability 只报告 `UP|DOWN`。liveness 只反映进程自身可服务，外部依赖暂时不可达不会使其 DOWN；readiness 必须聚合数据库/状态持久化、LLM、Embedding、Rerank、Supervisor 与五个专业 skill，任一 required 能力无效即 DOWN。真实模型探针完成后的 `KB_EMPTY` 只是数据状态。

`opspilot-v1.yaml` 是北向合同，所有 operation 必须有 Controller/DTO/Mapper 和合同测试。写请求强制 `Idempotency-Key`，按 `principal + operation + key` 保存 request hash 与原响应；相同请求重放原状态/响应，不同请求返回 409。SSE 只发布事务提交后的持久事件，按 Run 和 `Last-Event-ID` 校验归属并稳定重放，慢消费者或断连不影响核心事务。

### 决策十：关联标识由边界生成并逐层收窄

北向边界生成或验证 `requestId/traceId`，应用层补充 `runId/stepId`，A2A 与 Tool/Provider 边界补充 `a2aTaskId/invocationId`；调用方不能覆盖不属于其作用域的标识。结构化上下文通过 HTTP header、A2A metadata、事件和审计记录传播，MDC/telemetry 只保存受控摘要。

错误响应、A2A status、SSE 与日志共享稳定错误分类和关联 ID，但不包含 Secret、Prompt、原始正文、内部堆栈或跨租户存在性信息。大日志先保存为受权 Artifact，线路上只传 `logArtifactId`。审计查询从北向请求可追到 A2A Task、Tool invocation、Evidence 和 RCA，并对每一跳执行主体与 Run 归属校验。

## 风险与权衡

- [AgentScope 或 A2A `v1.0.1` 实际 API 与 Phase 0 证据漂移] → 实现前复跑 Spike 合同并锁定 Maven 坐标/生成对象；漂移时阻塞相应工作包，不自行猜测兼容层。
- [六进程、独立身份和真实网络使本地集成更慢] → 分离模块合同与 Compose 纵切，但最终门禁必须执行真实 HTTP+JSON、抓包和端口扫描。
- [严格 Artifact 校验和 analysis seal 拒绝迟到结果] → 在 seal 前执行 binding 对账并显式记录 `missingEvidence`；需要新证据时创建新 Run，保证已有报告可复现。
- [代码归属元数据不完整导致无法分析] → 明确返回 `CODE_SOURCE_NOT_CONFIGURED` 或 `CODE_REVISION_UNRESOLVED`；宁可缺失证据，也不回退 `main`、本地目录或模型 URL。
- [A2A stream、取消和重试竞态复杂] → 以持久 binding、CAS、终态单调和 Get/Subscribe 对账为唯一恢复依据，建立故障注入状态矩阵。
- [readiness 聚合全部 required skill 降低可用性] → 保持 liveness 独立并公开逐能力快照；不引入语义不完整的 `DEGRADED` 产品模式。
- [幂等响应与 SSE 事件长期保存增加存储量] → 复用既有 Artifact/保留策略和 outbox 清理规则，任何清理都必须保留活跃 Run 与审计引用。

## 迁移与回滚计划

1. 先新增 Agent/A2A/attempt/Directory/idempotency/analysis seal 所需向前兼容 migration、机器合同和 fixture，并验证旧数据可读。
2. 完成 AgentExecutionService、六 Profile 与九 Tool/Code Source/Sandbox 的离线合同，冻结 Registry 后再启用 A2A Server。
3. 部署五个专业服务的内网入口与独立身份，验证 Card、数据库授权和 endpoint probe，再启用 8080 Supervisor 委派。
4. 接入 Artifact 原子接收、analysis seal/RCA、健康聚合与 OpenAPI REST/SSE，最后运行完整 Compose 纵切和安全扫描。
5. 回滚时先关闭北向写流量与委派，等待或取消在途 Task，再回退应用镜像；保留新增表、终态 Task、Artifact、幂等响应和审计记录，不逆向删除 migration。重新启用前以 Directory/Card digest 和 binding 对账恢复。

## 待确认事项

- 实现 06-WP04 前必须从 Phase 0 证据复核 A2A `v1.0.1` 的最终 Maven 坐标、官方生成对象和 `subscribe` 绑定；若证据缺失则该工作包保持阻塞。
- 实现 06-WP01 前必须复核 AgentScope Spike 记录的真实 `ReActAgent`、Middleware、session/checkpoint 与取消 API；不得以设计文档中的伪 API 替代。
- 实现 06-WP03 前需要为开发/CI 冻结最小 GitHub/GitLab 只读 fixture 仓库、完整 commit SHA、连接 Secret ref 和允许的 Maven test suite ID。
