## 17. 异常处理、超时和重试策略

### 17.1 错误分类

| 错误 | 是否重试 | 处理 |
|---|---|---|
| 配置缺失、URL 非法、能力不支持 | 否 | 启动失败或任务 `FAILED`，返回配置路径/错误码 |
| 401/403、模型不存在 | 否 | `MODEL_AUTH_FAILED` / `MODEL_NOT_FOUND`，不切 Mock |
| 400/Schema/上下文超限 | 原请求否 | 由 Context Builder 缩小后可形成一个新受审计 attempt；不能原样重放 |
| 429 | 有上限 | 尊重 `Retry-After`，指数退避 + jitter，计入预算 |
| 网络连接、502/503/504 | 有上限 | 仅在总 deadline 内重试；readiness 为 DOWN，耗尽后返回 `ChainFailure` |
| 流式响应中断 | 默认不自动拼接 | 丢弃半成品最终结果；幂等且预算允许时从 checkpoint 重启整次调用 |
| 模型输出 Schema 无效 | 受限一次 | 同模型做一次结构修复；失败为 `MODEL_OUTPUT_INVALID` |
| Embedding 维度漂移/非有限值 | 否 | `EMBEDDING_DIMENSION_MISMATCH`，阻止写入/检索 |
| Rerank index/模型/分数合同无效 | 否 | `RERANK_RESPONSE_INVALID`，不声称已重排 |
| Infinity 进程或任一已配置模型不可用 | 有上限 | Embedding、Rerank capability 均置 DOWN；耗尽后返回关联日志的 `ChainFailure`，不切换其他 Provider 或删减检索步骤 |
| PostgreSQL CAS 冲突 | 可重读后少量重试 | 比较状态版本；不可盲写覆盖 |
| PostgreSQL 不可用 | 有上限 | 不继续执行 Agent；保留已提交 checkpoint |
| 专用 Registry ID 冲突/required 实现缺失 | 否 | 启动失败；不按扫描或 Bean 顺序覆盖 |
| 可选 SSE/metrics projector 失败 | outbox 有界重试 | 隔离投影失败，不回滚已提交诊断状态；超过错误预算告警 |
| Tool 权限/审批拒绝 | 否 | 记录 `DENIED`，Supervisor 重新规划或结束 |
| Token/轮数/工具预算超限 | 否无限重试 | 压缩/缩小/拆分后仍不足则 `BUDGET_EXCEEDED` |
| 知识库为空/无匹配/历史案例不足 | 否 | 合法业务结果；`COMPLETED + NO_MATCH/INSUFFICIENT_HISTORY`，继续现场证据诊断 |
| A2A 版本/媒体类型/required protocol extension 不兼容 | 否 | 返回标准协议错误，刷新受信 Agent Card 后仍不兼容则拒绝委派 |
| A2A stream 中断/响应不确定 | 不盲重发 | 先 Get/Subscribe 原 Task；确认不存在后才按 messageId 幂等重建 |
| 连续补证无新 Evidence | 否 | `NO_PROGRESS`，结束为 `PARTIAL/INCONCLUSIVE` 或请求明确输入 |

### 17.2 超时层级

```text
Incident 总 deadline
  > Agent step deadline
    > Provider/Tool request deadline
      > connect timeout + response/read timeout
```

子层超时之和与退避时间不能超过父层 deadline。配置示例值是上限而非保证；超时后取消 HTTP 请求、释放 Provider semaphore、记录 attempt 和 Token Usage，并写可恢复状态。

### 17.3 重试与并发

- LLM 默认总尝试不超过 2；Embedding/Rerank 默认总尝试不超过 2，具体可按幂等性配置。
- 指数退避带随机抖动，防止多个 Agent 同时重试；429 使用服务端 `Retry-After` 与本地上限的较小可接受值。
- 每个 Provider Profile 使用独立 semaphore/bulkhead；Agent 并发限制不能突破 Provider 限制。
- 重试次数、失败调用 Token、等待耗时全部计入任务预算和审计。
- 默认不做自动 Provider failover；显式真实备用 Provider 也必须能力等价、通过探针并记录实际路由。

### 17.4 任务级恢复

- Evidence Source 的关键性由场景合同决定，不能由模型临时判断。默认矩阵如下；场景可以把 `CONDITIONAL` 提升为 `MANDATORY`，不得降级 `MANDATORY`：

| 能力 | 默认级别 | 可继续条件 | 技术失败结果 |
|---|---|---|---|
| Incident ticket、Artifact 服务、PostgreSQL 状态库、LLM、A2A endpoint | `MANDATORY` | 无 | step 与 Incident `FAILED` |
| `LogQueryTool` | `MANDATORY` | 无 | step 与 Incident `FAILED` |
| `HealthQueryTool` | `MANDATORY` | 无 | step 与 Incident `FAILED` |
| `MetricQueryTool` | `CONDITIONAL` | 场景未声明 required metric evidence，且日志/Trace 至少一种独立观测源成功 | 记录 `missingEvidence`，step 可完成 |
| `TraceQueryTool` | `CONDITIONAL` | 场景未声明 required trace evidence，且日志/指标至少一种独立观测源成功 | 记录 `missingEvidence`，step 可完成 |
| `TopologyQueryTool` | `CONDITIONAL` | 场景已提供版本化静态拓扑，且当前假设不依赖动态实例关系 | 记录 `missingEvidence`，step 可完成 |
| `ConfigReadTool` | `CONDITIONAL` | 当前假设不依赖配置事实 | 记录 `missingEvidence`，step 可完成 |
| `CodeSearchTool` | `CONDITIONAL` | 计划明确无需代码定位 | step `SKIPPED`；若已进入调用后技术失败则 Incident `FAILED` |
| Embedding、Rerank、`KnowledgeSearchTool`、KnowledgeAgent | `MANDATORY_WHEN_CANDIDATES_EXIST` | 空库/零候选是成功业务结果；存在候选时必须完成 Rerank | 技术失败时 Incident `FAILED` |
| `SandboxTestTool` | `OPTIONAL_APPROVED` | 未批准、拒绝或超时均可生成受限报告 | 已批准后沙箱技术失败则 Incident `FAILED` |

只有表中明确允许继续的 `CONDITIONAL` 空缺才能形成结构化 `missingEvidence`。HTTP 超时、鉴权失败或无效 Schema 仍必须记录 `ChainFailure`；Supervisor 根据矩阵决定该 failure 是终止 Incident 还是作为受限证据缺口继续。E2E 必须分别覆盖“允许缺失”和“强制失败”两类路径。
- 可观测 Source 的未配置、空结果、技术失败和部分结果严格按第 27.9 节区分；每个 ChainFailure/missingEvidence 必须携带 `sourceId/sourceKind/adapterId`，不得只记录抽象 Tool 名。
- 知识库为空、无匹配或无历史案例时，KnowledgeAgent 返回 `COMPLETED` 的结构化空结果。Rerank、Embedding、KnowledgeAgent 或 A2A/Tool 技术链路不可用时必须返回 `ChainFailure`；关键能力有限重试后 `FAILED`，仅第 17.4 节明确允许的 conditional 证据源可以记录 `missingEvidence` 后继续。禁止 vector-only、关键词、Mock、固定结果、替代 Provider 或未记录原因地跳过步骤回退。
- LLM 不可用、状态无法持久化或 Ground Truth 隔离异常属于关键失败，任务不能伪装完成。
- 错误经 SSE 发布 `ERROR`，包含 `errorCode`、`retryable`、`stepId`、脱敏消息和 `requestId`；完整异常进入受控日志。

`ERROR` 事件、A2A Task status message 和 REST 错误体必须带相同 `requestId/traceId/runId/stepId/a2aTaskId/invocationId`。日志保存每次 attempt 的开始/结束、目标组件、耗时、上游状态/请求 ID、重试决策、脱敏 cause chain 和 checkpoint，并通过 `logArtifactId` 受控访问；禁止只返回“调用失败”这类不可定位文本。

每次补证记录标准化输入指纹、`newEvidenceCount`、`hypothesisDelta` 和置信度变化；重复输入且数据版本未变化时不再调用。连续两轮无新证据或假设变化即触发 `NO_PROGRESS`。轮数、A2A Task 数、Tool 数、单假设验证次数、Token、成本和 deadline 任一到达上限时必须停止，模型无权提高预算。

## 18. 安全设计

### 18.1 数据库与 Ground Truth

- `opspilot_migrator` 只用于迁移；`opspilot_app_role` 只访问 `opspilot` 必要表；`sample_app_role` 只访问 `sample`；`evaluation_role` 独占 `opspilot_eval`。
- Agent Server 不获得 `opspilot_eval` schema `USAGE`，也不挂载 Ground Truth 卷；Evaluation 使用单独进程/凭证读取，再只写评分结果。
- 数据库启用 TLS（生产）、连接最小权限和定期备份；应用 SQL 全部参数化，动态向量 cast 只接受已验证整数和 allowlist 运算符。

### 18.2 密钥与配置

- 本地通过未提交的 `.env`/Docker Secret 注入；生产通过外部密钥管理系统。数据库只存 `env:NAME` 等 secret ref。
- 日志、Trace、错误、模型 Prompt、Tool 输入摘要和 Actuator 统一经过 `SensitiveDataMasker`；Key、密码、Token、完整 JDBC 凭证永不回显。
- Provider Base URL 需通过协议/域名 allowlist，禁止任务内容覆盖端点，降低 SSRF 和数据外传风险。

### 18.3 Tool 与审批

- Tool 的固定调用链为 `Schema → Authorize → Approval → Budget/Deadline → Execute → Normalize/Redact → Audit`；安全步骤由核心装配，Tool 或 Adapter 不能覆盖、跳过或重排。
- `READ_ONLY` 默认允许，但仍做输入 Schema、资源范围、超时和返回量检查。
- `CONTROLLED_EXECUTION` 只允许配置白名单中的 Maven 测试/测试容器动作，按策略审批。
- `HIGH_RISK` 在 MVP 禁止；即使模型请求或用户审批，也不能执行任意 Shell、代码/配置写入、Git 写入/分支切换/任意 Git 命令、生产或任意数据库修改。平台固定的 `CodeSourceAdapter` 只能以只读凭证从 allowlist 仓库获取精确 commit，不把 Git 能力暴露给 Agent。
- Docker socket 仅给 Fault Lab 场景编排器；Agent Server 和 Sandbox Tool 不持有宿主 Docker 控制权。
- 每个调用记录主体、权限、输入/输出摘要、审批、时间、结果和错误；审计记录不可由 Agent 修改。

### 18.4 文件与 Artifact

- `ArtifactAccessService` 将逻辑 ID 解析到允许根目录；拒绝绝对路径、`..`、符号链接逃逸、设备文件和超限文件。
- Agent 输入卷只读；Ground Truth 与 execution 敏感卷不挂载给 Agent。
- Artifact 写入先到临时文件，计算 SHA-256 后原子移动并登记；读取校验哈希和访问级别。
- 日志/Trace/代码片段在进入模型前脱敏、限长，避免把数据库密码、Key 或个人数据发送给外部 LLM。

### 18.5 Prompt 与知识安全

- 检索文档和故障日志被标记为“不可信数据”，其中的指令不能改变 System Prompt、工具权限或输出 Schema。
- 工具参数必须来自结构化 Agent 输出并经 Policy 校验，不能直接执行检索文本中的命令。
- 报告引用 Evidence/Chunk ID；CitationValidity 验证引用存在且属于当前 Incident/可访问 collection。
- 不持久化或跨 Agent 传递隐藏思考过程，只保留决策、证据、结论和必要摘要。

### 18.6 API 与网络

- 本地端口绑定 `127.0.0.1`。若生产暴露 API，必须在网关加入认证、授权、限流和 TLS；具体身份系统 **待确认**，MVP 不虚构企业 RBAC。
- A2A Agent 间调用使用独立服务身份；本地为短期服务 Token，生产采用 mTLS 或 OAuth2 client credentials。Agent Card URL、A2A endpoint 和证书/签名进入 allowlist，模型不能修改目标地址。
- A2A Task 按调用方和 skill scope 授权；专业 Agent 的数据库角色不能写 Supervisor 领域表。受控 Artifact URL 必须短期、限任务、限媒体类型，并在接收端再次校验哈希和访问级别。
- PostgreSQL、Infinity、Toxiproxy 默认不暴露公网；生产出站策略只允许批准的模型域名和可观测端点。
- 模型/镜像/依赖使用锁定版本或 digest，保留 SBOM、License、漏洞扫描和构建来源证明；第 26 章持续交付把这些证据写入 Release Manifest，但不自动部署。

## 19. 可观测性与 Token 用量统计

### 19.1 结构化日志与 Trace

所有 Java 服务输出 JSON 日志，至少包含：

```text
timestamp, level, service, traceId, spanId, requestId,
incidentId, runId, agentName, invocationId, toolCallId,
a2aContextId, a2aTaskId, a2aMessageId, remoteAgentId,
providerId, adapterId, capabilityVersion,
logger, thread, message, errorCode, exception
```

OpenTelemetry Trace 覆盖 REST → Agent step → Model/Tool → PostgreSQL/RAG。完整 Prompt、模型响应、密钥和大工具输出不作为 span attribute；只记录长度、哈希、引用和脱敏摘要。统一 UTC，展示层转换时区。

### 19.2 指标

保留被测系统指标：HTTP 请求、JVM 内存/线程/CPU、Hikari active/idle/pending/max。新增低基数 OpsPilot 指标：

```text
opspilot_incident_runs_total{status}
opspilot_agent_steps_total{agent,status}
opspilot_agent_rounds{agent}
opspilot_tool_calls_total{tool,status,permission}
opspilot_model_requests_total{agent,provider,model,status}
opspilot_model_latency_seconds{agent,provider,model}
opspilot_model_tokens_total{agent,model,type}
opspilot_model_cost_estimate_total{agent,model,currency}
opspilot_token_budget_exceeded_total{agent}
opspilot_rag_retrieval_seconds{strategy}
opspilot_rag_candidates{stage}
opspilot_embedding_jobs_total{status}
opspilot_pgvector_query_seconds{index_strategy}
```

`incidentId/runId` 不作为 Prometheus label，避免高基数；它们只在日志/Trace/数据库中关联。

### 19.3 Token 与成本账本

`model_invocation` 是调用级账本：记录协议、Provider/模型/revision、Agent、配置/Prompt 版本、Context Snapshot 引用、输入/输出/缓存 Token、估算标记、价格版本/币种、延迟、retry/attempt、上游请求 ID、状态和错误。`UsageStatistics` 按 Agent 和 Incident 聚合。

价格不硬编码在代码；受控外部配置导入 `pricing_profile`，记录生效时间、币种和输入/输出/缓存单价版本，并在调用行固定 `pricing_profile_id`。Provider 没有返回 Usage 时采用保守估算并写 `usage_estimated=true`；成本字段始终标识估算，不把它当账单真值。

### 19.4 Dashboard 与告警

- Provider 成功率、429/5xx、p95、并发队列、Token/Incident、预算超限；
- Agent 轮数、Tool 调用数、等待审批时长、任务失败/恢复；
- pgvector 检索 p95、candidate/top-k、Recall 离线指标、Embedding job backlog；
- PostgreSQL 连接池、锁等待、WAL/磁盘、慢查询；
- Ground Truth 越权尝试、HIGH_RISK 拒绝、路径穿越拒绝和密钥脱敏事件。
