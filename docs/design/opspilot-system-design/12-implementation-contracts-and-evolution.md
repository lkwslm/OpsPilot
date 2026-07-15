## 23. 实现合同与演进规则

本章把前述架构说明收敛为首版实现必须遵守的稳定合同。目标不是冻结所有业务字段，而是冻结最难迁移的边界：领域身份、状态所有权、数据库表职责、跨进程协议和评测输入输出。首版之后新增能力优先通过新增字段、Artifact Schema 新版本、第 28 章限定的受控 Adapter 或独立表扩展，避免重命名核心 ID、移动表所有权或把稳定核心改造成动态插件。

### 23.1 权威合同及优先级

权威来源按以下优先级解释：

1. `docs/design/contracts/` 中的 OpenAPI 与 JSON Schema；
2. 第 6.3 节状态转换矩阵、第 17.4 节能力关键性矩阵和本章不变量；
3. 第 28 章的核心/扩展边界与模块交互规则；
4. 数据表和其他模块边界章节；
5. Mermaid 图、示例 JSON 和说明文字。

若低优先级内容与高优先级合同冲突，必须修正文档，不能在代码中选择性解释。所有合同文件使用语义版本；兼容新增字段只增加 minor，删除、重命名、改变含义或收紧既有合法值必须增加 major 并通过 ADR。

### 23.2 首版冻结的领域身份

以下 ID 从首版起稳定且全局使用字符串形式的 UUID；API 不暴露数据库自增键：

| 对象 | 字段 | 生成者 | 生命周期 |
|---|---|---|---|
| Incident | `incidentId` | OpsPilot API | 不复用，不物理删除审计关联对象 |
| Incident Run | `runId` | OpsPilot API | 一个 Incident 可有多个历史 Run，但最多一个活动 Run |
| step | `stepId` | Supervisor | 在单个 Run 内唯一；plan 修订不改变已执行 step ID |
| attempt | `attempt` | Supervisor | `(runId, stepId, attempt)` 唯一，单调递增 |
| A2A Task | `a2aTaskId` | 对应 A2A Server | 与 `remoteAgentId` 组成全局唯一身份 |
| Evidence | `evidenceId` | 结果接收事务 | 在 Run 内不可变；修订生成新 Evidence |
| Artifact | `artifactId` | Artifact 服务 | 内容寻址元数据不可覆盖，更新生成新 Artifact |
| Hypothesis | `hypothesisId` | Diagnosis 结果接收事务 | 在 Run 内唯一 |
| Root Cause | `rootCauseCode` | 场景/领域目录 | 跨 RCA 与 Ground Truth 的规范化稳定代码 |

`rootCauseCode` 使用小写点分命名，例如 `dependency.latency.inventory`、`database.pool.exhausted.order`、`service.instance.stopped.inventory`。展示标题、自然语言描述和模型输出不能替代该代码。未知或无法收敛时为 `null`，不得生成临时代码。

### 23.3 北向 API 合同

机器可读合同位于 `docs/design/contracts/openapi/opspilot-v1.yaml`。首版统一规则：

- 基路径 `/api`，JSON 使用 `application/json`，SSE 使用 `text/event-stream`；
- 所有写请求必须带 `Idempotency-Key`，作用域为 `principal + operation + key`；
- 创建成功返回 `201`，异步 Run/恢复/取消接受返回 `202`；
- 业务冲突返回 `409`，Schema/参数错误返回 `400`，不存在或无权查看统一返回 `404`；
- 错误统一使用 `ErrorResponse`，不得返回框架异常结构；
- 每个响应包含 `requestId`；与 Run 相关时包含 `incidentId/runId`；
- `GET /events` 只作为 SSE，事件 payload 必须通过 `incident-event.schema.json`；
- `runId` 查询参数必须验证归属；未提供时使用当前活动 Run，否则使用最近 Run；
- 报告在尚未生成时返回 `409 REPORT_NOT_READY`，不返回空对象或 `200 null`。

API 的兼容演进只允许：新增可选请求字段、新增响应字段、新增 endpoint、新增错误码。既有字段改名、类型变化、枚举语义变化或同步/异步语义变化需要 `/api/v2`。

### 23.4 A2A 与 Artifact 合同

六个 skill 使用以下稳定媒体类型：

| Skill | 请求媒体类型 | 结果媒体类型 |
|---|---|---|
| `incident-investigation` | `application/vnd.opspilot.investigation-request+json;v=1` | `application/vnd.opspilot.rca+json;v=1` |
| `collect-observability-evidence` | `application/vnd.opspilot.evidence-request+json;v=1` | `application/vnd.opspilot.evidence-bundle+json;v=1` |
| `analyze-code-location` | `application/vnd.opspilot.code-analysis-request+json;v=1` | `application/vnd.opspilot.code-findings+json;v=1` |
| `retrieve-incident-knowledge` | `application/vnd.opspilot.knowledge-request+json;v=1` | `application/vnd.opspilot.knowledge-result+json;v=1` |
| `generate-and-verify-hypotheses` | `application/vnd.opspilot.diagnosis-request+json;v=1` | `application/vnd.opspilot.diagnosis-assessment+json;v=1` |
| `propose-remediation` | `application/vnd.opspilot.remediation-request+json;v=1` | `application/vnd.opspilot.remediation-plan+json;v=1` |

请求和结果的机器 Schema 位于 `docs/design/contracts/schemas/a2a-skill-contracts.schema.json`。Source Adapter 输出使用 `application/vnd.opspilot.observation-batch+json;v=1`，Evidence Agent 最终 Artifact 使用表中的 EvidenceBundle 媒体类型。任何接收端必须按以下顺序验证：媒体类型 → major schema version → JSON Schema → Source/Resource/Task/Run 归属 → Artifact 哈希 → 引用权限 → 领域不变量。验证失败不得部分写入领域表。

新增可选字段保持 v1；改变 required 字段、枚举含义或引用语义必须发布 v2 媒体类型。Client 在 Agent Card 中只选择自己明确支持的 major version，禁止忽略未知 major 后继续执行。

### 23.5 Tool 合同

九个 Tool 的稳定输入输出定义在 `tool-contracts.schema.json`。日志、指标、Trace、健康和拓扑使用能力名称，不使用具体产品名；它们的 Source Adapter、ObservationBatch 和 EvidenceBundle 合同见第 27 章。通用约束：

- 所有查询必须带 `runId` 和结果上限；日志、指标、Trace、事件查询必须带受控时间窗，健康/配置/拓扑使用有界快照语义；
- Tool Runtime 从执行上下文获取授权根目录、服务 allowlist 和数据分级，模型不能传入这些权限；
- 返回值只包含摘要和 Artifact/Evidence 引用，大正文写 Artifact；
- `status` 只允许 `SUCCEEDED/EMPTY/DENIED/FAILED`；
- `EMPTY` 是成功业务结果，`FAILED` 必须带稳定 `errorCode`；
- Tool Schema major version 与 `toolName` 一同进入审计和动作指纹。
- 可观测 Tool 结果必须包含 `observationBatchIds` 和可空 `evidenceBundleId`；成功且形成 Evidence 时后者非空。Agent、RCA 和 Evaluation 只消费标准 Evidence/Artifact 引用，不消费 Prometheus、Loki、Jaeger、Tempo、OTel、Kubernetes 或 Cloud DTO。

### 23.6 数据库首版冻结边界

以下 schema 和表所有权首版冻结：

- `opspilot`：产品领域、Run、状态投影、Evidence、RCA、审计、配置、RAG 元数据；只有 Server/Supervisor 应用服务写领域表；
- `opspilot_a2a`：六个 A2A Server 各自的 Task/Message/Artifact/Event 与 AgentScope runtime state；按 `server_agent_id` 和数据库角色隔离；
- `opspilot_eval`：Ground Truth；只允许 Fault Lab 和 Evaluation；
- `sample`：被测业务数据；只允许 Sample System。

核心表禁止在首版后改变主键语义或移动 schema。演进规则：

1. 使用 expand-and-contract：先新增可空列/新表和双读，再回填、切读，最后在下一发布窗口删除旧结构。
2. 领域 JSONB 必须带 `schemaVersion`；高频查询、唯一约束、外键和状态不得只藏在 JSONB。
3. 枚举在数据库使用 `varchar + CHECK`，避免 PostgreSQL enum 难回滚；增加枚举值先扩 CHECK，再发布代码。
4. 审计、模型调用、状态转换和 Evaluation 结果只追加，不随 Incident 级联删除。
5. Artifact 元数据记录 `storageProvider/objectKey`，`uri` 只作为兼容读取字段；本地卷迁移对象存储时不改变 Artifact ID 或领域引用。
6. 时间统一 `timestamptz`、金额统一定点 decimal、哈希统一小写十六进制 SHA-256、JSON 字段统一 camelCase。
7. 每个 Flyway migration 必须有从生产前一版本升级的 Testcontainers 测试；禁止依赖 Hibernate 自动建表。

为避免未来把 `uri` 重构为对象存储时修改所有调用方，`opspilot.artifact` 首版应包含：`artifact_id, run_id, storage_provider, object_key, uri, sha256, size_bytes, media_type, access_level, lifecycle_status, created_at, expires_at`。所有业务模块只持有 `artifactId`，只有 `ArtifactAccessService` 解释存储位置。

### 23.7 数据保留与删除

首版本地默认保留：Incident/Run/审计 30 天、原始观测 Artifact 14 天、RCA/Evaluation 90 天、Ground Truth 永久保留至场景版本废弃。删除任务先把 Artifact 标为 `DELETE_PENDING`，确认不受保留策略和引用保护后删除对象，再写 `DELETED`；对账任务负责处理孤儿对象和孤儿元数据。保留期可配置，但删除流程和引用检查不可绕过。

### 23.8 Definition of Ready

某模块进入功能编码前必须同时满足：

- 对应 OpenAPI/JSON Schema 已存在并通过语法校验；
- 领域 ID、状态所有者、数据库角色和事务边界已明确；
- 正常、空结果、技术失败和取消四类验收用例已定义；
- 依赖版本已由 Phase 0 Spike 锁定；
- 若实现属于多实现扩展点，稳定 ID、专用 Registry、冲突规则、能力探针和共享 contract suite 已明确；若不属于第 28.4 节五类边界，不得自行引入动态 Extension 机制；
- 不要求实现者自行发明跨模块字段或状态转换。

不满足上述条件时只允许进行 Spike，不允许把临时代码作为正式实现继续叠加。
