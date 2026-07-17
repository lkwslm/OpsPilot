# 阶段 03：PostgreSQL/pgvector、领域持久化与 Artifact

## 目标

完成 PostgreSQL 唯一业务事实源、pgvector 知识存储、四 schema/角色隔离、事务 outbox、来源追溯和 Artifact 存储 Adapter，并在 composition root 显式装配 Repository。

## 前置门禁

- core 的状态、Port、事务边界和冻结 ID 已通过阶段 02。
- 使用阶段 01 锁定的 PostgreSQL/pgvector 版本和镜像 digest。

## 建设范围

1. 按顺序实现 Flyway：扩展/schema、核心 Incident、Sample、模型与用量、知识/向量、Evaluation 隔离、关系索引和非敏感种子配置。
2. 建立 `opspilot`、`opspilot_a2a`、`sample`、`opspilot_eval`，以及 `opspilot_migrator`、`opspilot_app_role`、`sample_app_role`、`fault_lab_role`、`evaluation_role` 和各专业 Agent 角色；只有 Fault Lab/Evaluation 角色可访问 `opspilot_eval`，Agent/app/Tool 数据源和模型上下文无该 schema 的 `USAGE`/`SELECT`，专业 Agent 无 Supervisor 领域表写权限。每个专业角色只能访问自己的 `opspilot_a2a` 行，使用 RLS，或使用按 `server_agent_id` 的受控 Repository 谓词并叠加数据库角色测试双重保证；专业 Agent 不使用 `opspilot_app_role`。
3. 实现第 10.1–10.5 节全部职责表，包括 task 租约、API 幂等、Agent state、A2A binding、状态历史、目标资源/拓扑、可观测 Source、Code Source/Repository/部署 revision 绑定/CodeSnapshot、Observation、Evidence/provenance、Hypothesis 及 Evidence/Verification 关系、RCA 元数据、调用审计、ChainFailure、Approval、SSE、Artifact、Evaluation、模型/Prompt/Usage 和知识版本表。
4. 用数据库约束落实单活动 Run、单活动 attempt、单活动文档版本、API/Tool/message/A2A 幂等、核心状态 CHECK、外键生命周期和追加写审计；数据库枚举统一使用 `varchar + CHECK`，高频查询字段、唯一约束、外键和状态不得只藏在 JSONB。
5. 实现 Repository/DAO：常规 CRUD 使用 JPA；向量距离/Top-K 使用受控 JDBC/jOOQ；所有动态维度和运算符来自已验证值与 allowlist。
6. 实现 pgvector revision 合同：真实维度、度量、归一化、有限值、COSINE 非零范数、复合外键和 `vector_dims` 双校验。MVP 小于 50,000 active Chunk 时使用过滤后精确扫描，不默认创建 ANN。
7. 实现基础知识版本的持久化能力：文档版本、Chunk、导入 checkpoint、同 revision 内容哈希复用、revision 覆盖状态、active revision/searchable 原子切换和回滚窗口；本阶段只提供 Repository、约束和事务原语，不调用 Embedding/Rerank，也不编排导入或重向量化流程。
8. 实现 ArtifactAccessService 和本地卷 Adapter：临时写入、SHA-256、原子移动、访问级别、受控路径、生命周期、保留/删除/对账；业务只持有 `artifactId`，内容寻址元数据不可覆盖，更新必须生成新 Artifact。首版本地默认保留 Incident/Run/审计 30 天、原始观测 Artifact 14 天、RCA/Evaluation 90 天，Ground Truth 永久保留到场景版本废弃；删除必须按 `DELETE_PENDING → 删除对象 → DELETED` 执行并通过引用保护与孤儿对账。
9. 实现状态/审计/outbox 的同事务持久化、PostgreSQL task `SKIP LOCKED` 租约恢复、SSE `Last-Event-ID` 的按 Run 重放。
10. 为每条 Flyway migration 建立空库与生产前一版本升级 Testcontainers 测试，禁止 Hibernate 自动建表和 H2 替代。

## 详细实施计划

### 03-WP01：数据库启动、Flyway 基线与角色预置

**输入：**阶段 01 版本锁、第 9.1–9.2、10.8、23.6 节。

**代码/配置落点：**`deployment/postgres/init/`、`opspilot-adapters/persistence-postgres` 的 Flyway、数据源配置和 migration 入口。

**任务：**

1. `03-WP01.T01` 由初始化脚本只创建数据库、登录角色和最小密码注入；DDL/扩展只由 `opspilot_migrator` 执行。
2. `03-WP01.T02` 按 V1–V7 和可重复非敏感 seed 顺序建立 migration；命名、校验和及执行顺序不可由应用启动逻辑临时改变。
3. `03-WP01.T03` 创建 `vector` 扩展及 `opspilot/opspilot_a2a/sample/opspilot_eval`，设置默认权限，防止 future table 被错误授予 `PUBLIC`。
4. `03-WP01.T04` 建立 app、Sample、Fault Lab、Evaluation 和五个专业 Agent 角色；长期进程不获得 migrator 凭证。
5. `03-WP01.T05` 设置应用 `ddl-auto=validate`，启动时检查 Flyway 版本和 pgvector extversion；缺失时 readiness DOWN。

**验证：**空库迁移成功；应用角色执行 DDL/`CREATE EXTENSION` 失败；Migration 失败时应用不接收流量。

### 03-WP02：领域表、关键列与数据库不变量

**输入：**第 6.3、10.1–10.5、23.2、23.6 节。

**代码/配置落点：**核心/模型/知识/Evaluation Flyway、JPA Entity/映射和约束测试。

**任务：**

1. `03-WP02.T01` 建立 Incident/Run/task/api_idempotency/agent_state/endpoint/A2A binding/state_transition 表，核心状态使用 `varchar + CHECK`。
2. `03-WP02.T02` 建立 Target System/Resource/Relation、Observability Source、Code Source/Repository、Resource-Code Binding、CodeSnapshot、ObservationBatch/Record、Evidence/provenance 表；Source、Repository、Resource、Run 归属使用外键或可验证约束，源码正文不进入数据库。
3. `03-WP02.T03` 建立 Hypothesis、Hypothesis-Evidence、Hypothesis-Verification、RCA Report、Tool/Model 调用、ChainFailure、Approval、SSE、Artifact、Evaluation 结果表；支持/冲突 Evidence 和验证结果使用结构化关系，不用不可查询的自然语言或隐藏思考代替；审计/调用/转换采用追加写语义。
4. `03-WP02.T04` 建立 `opspilot_a2a` Task/Message/Artifact/Event 与 Agent runtime state；键包含 `server_agent_id`，不与 Supervisor 快照混表。
5. `03-WP02.T05` 建立 Sample 订单/库存表、模型/Profile/Prompt/Pricing/Usage 表、知识文档/版本/Chunk/revision/job 表和 Ground Truth 表。
6. `03-WP02.T06` 确保 Artifact 包含 `storage_provider/object_key/uri/sha256/size_bytes/media_type/access_level/lifecycle_status/expires_at`；业务表只保存 `artifactId`。
7. `03-WP02.T07` 为 JSONB 设置 `schemaVersion` 规则；状态、唯一键、外键、高频过滤字段不得只存在 JSONB。

**验证：**Schema 快照与设计表职责逐项比对；未知核心状态、缺外键、非法哈希/时间/金额格式均被数据库或映射层拒绝。

### 03-WP03：并发、幂等、索引与生命周期约束

**输入：**第 6.4、10.7、23.2 节。

**代码/配置落点：**关系索引 migration、Repository 冲突映射、并发集成测试。

**任务：**

1. `03-WP03.T01` 创建单 Incident 单活动 Run 的部分唯一索引，活动定义严格复用非 `COMPLETED/FAILED/CANCELLED` 集合。
2. `03-WP03.T02` 创建单 step 单活动 attempt、messageId、远端 Task、API、task 和 Tool 幂等唯一约束。
3. `03-WP03.T03` 创建单文档 ACTIVE 版本、Chunk ordinal、向量复合主键和 Source 配置身份约束。
4. `03-WP03.T04` 为 Run、Task 领取、Evidence/Observation、Code Source/Repository/部署 revision/CodeSnapshot、Hypothesis 关系、RCA 唯一 Run、SSE、模型调用和知识过滤创建设计规定的关系索引。
5. `03-WP03.T05` 为外键选择 RESTRICT/CASCADE；Incident 删除不得级联删除审计、模型调用、状态转换或 Evaluation 结果。
6. `03-WP03.T06` 把数据库冲突映射为稳定领域错误，特别是 `409 INCIDENT_ACTIVE_RUN_EXISTS` 和幂等 hash 冲突。

**验证：**并发创建 Run/attempt 只有一个提交；同键同请求重放、不同 hash 冲突；查询计划使用预期关系索引。

### 03-WP04：数据库角色、RLS 与 Ground Truth 隔离

**输入：**第 10.5、18.1、23.6、24.3 节。

**代码/配置落点：**Grant/RLS migration、角色专用 DataSource/Repository、权限矩阵测试。

**任务：**

1. `03-WP04.T01` 授予 `opspilot_app_role` 仅 Server/Supervisor 所需 DML；专业 Agent 无 Incident/Run/Hypothesis/RCA 写权限。Code Agent 仅可按 `runId + repositoryId` 读取最小 `code_analysis_scope` 视图，无 Incident/Run/Resource/Binding 底表权限；零行或多个未消歧 commit 均拒绝。
2. `03-WP04.T02` 以 RLS 或受控 `server_agent_id` 谓词隔离每个专业角色的 A2A/AgentState 行，并叠加数据库角色测试。
3. `03-WP04.T03` `sample_app_role` 仅访问 `sample`；Fault Lab 拥有故障编排所需最小权限但不获得 Agent 身份。
4. `03-WP04.T04` 仅 `fault_lab_role/evaluation_role` 可读取 `opspilot_eval`；Evaluation 只获得写 `opspilot.evaluation_result` 和必要 Artifact 元数据的权限。
5. `03-WP04.T05` 验证 app/Agent/Tool/model Context Builder 对 Ground Truth 无 schema `USAGE`、表 `SELECT` 和卷路径。
6. `03-WP04.T06` 建立每个角色的允许/拒绝 SQL 清单，测试同时覆盖当前表和 future table 默认权限。

**验证：**权限矩阵中每个拒绝项实际返回数据库权限错误；跨 `server_agent_id` 查询/更新为零行或拒绝且写审计。

### 03-WP05：Repository、UnitOfWork 与持久 checkpoint

**输入：**阶段 02 Port/事务合同、第 9.8、28.10 节。

**代码/配置落点：**JPA Repository、受控 JDBC/jOOQ、UnitOfWork、outbox publisher/projector 和集成测试。

**任务：**

1. `03-WP05.T01` 为常规 CRUD 建立 JPA 映射，显式处理 JSONB、timestamptz、decimal 和乐观锁；不暴露 Entity 到 core。
2. `03-WP05.T02` 实现 `IncidentAgentStateRepository.compareAndSet`，检查表 version 与 JSON 内 version 一致及必需引用归属。
3. `03-WP05.T03` 实现结果接收 UnitOfWork，同事务更新 Run/step/快照、A2A binding、调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox。
4. `03-WP05.T04` 实现分析封账写入保护：`analysis_sealed_at` 非空后拒绝该 Run 新增或修改 Evidence、Hypothesis、Hypothesis-Evidence、Hypothesis-Verification；报告失败重试只读同一封账数据，新证据只能进入新 Run。
5. `03-WP05.T05` 实现 eventId 幂等 publisher/projector；事务提交前事件不可见，重复消费不重复投影。
6. `03-WP05.T06` 为每个关键写入点注入故障，验证全部提交或全部回滚；CAS 冲突重读后重新判定状态。

**验证：**数据库集成测试证明原子提交、回滚无残留、CAS 不覆盖和 outbox 重放幂等。

### 03-WP06：pgvector revision 与精确检索 Repository

**输入：**阶段 01 探针结果、第 9.3–9.6 节。

**代码/配置落点：**向量 migration、`KnowledgeVectorRepository` JDBC/jOOQ、查询模板和 Testcontainers 数据集。

**任务：**

1. `03-WP06.T01` 使用无 typmod `vector`，以 `(model_revision_id, embedding_dimension)` 复合外键和 `vector_dims` 双重校验维度。
2. `03-WP06.T02` 在应用层拒绝 NaN/Infinity；COSINE 在数据库触发器/约束与应用层拒绝零范数。
3. `03-WP06.T03` 将 distance metric/normalization 作为 revision 不可变属性；运算符仅从 COSINE/INNER_PRODUCT/L2 allowlist 映射。
4. `03-WP06.T04` 实现带 collection、active revision、`searchable=true`、文档状态和元数据过滤的精确 Top-K；query dimension/revision 必须匹配。
5. `03-WP06.T05` 在 `< 50,000` active Chunk 基线下不创建 ANN；迁移中不得包含默认 HNSW/IVFFlat。
6. `03-WP06.T06` 对 SQL 参数、动态维度和 operator 注入编写负向测试，禁止配置文本直接拼 SQL。

**验证：**正确 revision 排序可预测；维度漂移、跨 revision、非有限/零向量及非法 operator 全部失败；Schema 无 ANN 索引。

### 03-WP07：知识版本与原子切换持久原语

**输入：**第 9.7、10.4、附录 A.2；本阶段不调用 Provider。

**代码/配置落点：**Knowledge Document/Version/Chunk/Job Repository、revision 状态机和事务测试。

**任务：**

1. `03-WP07.T01` 实现文档、版本、Chunk 和 ingestion job/checkpoint 的创建、读取及追加更新原语。
2. `03-WP07.T02` 同 revision 内容哈希复用时为新 chunk 插入独立向量行，并重新校验哈希/维度；禁止跨 revision 复用。
3. `03-WP07.T03` 实现 coverage、失败 Chunk、job attempts 和 last_error 持久化，失败不得静默丢 Chunk。
4. `03-WP07.T04` 实现 document version/collection active revision 与新旧 `searchable` 的同事务切换。
5. `03-WP07.T05` 实现 deleted_at 立即过滤、回滚到旧 revision 和保留窗口所需原语；不在本阶段编排 Provider 调用。

**验证：**覆盖不完整不能切换；切换事务失败继续使用旧 revision；回滚后固定查询恢复旧结果。

### 03-WP08：ArtifactAccessService 与生命周期

**输入：**第 18.4、21.5、23.6–23.7 节。

**代码/配置落点：**core Artifact Port、受控本地卷 Adapter、元数据 Repository、保留/删除/对账任务。

**任务：**

1. `03-WP08.T01` 实现受控根目录和 `artifactId → storageProvider/objectKey` 解析；拒绝绝对路径、`..`、设备文件和符号链接逃逸。
2. `03-WP08.T02` 写入临时文件，计算 SHA-256/大小/媒体类型后原子移动和登记；同 artifactId 的内容寻址元数据不可覆盖。
3. `03-WP08.T03` 读取时校验访问级别、Run/Task 权限、哈希和大小；大文件使用受控流和上限。
4. `03-WP08.T04` 配置 Incident/Run/审计 30 天、原始观测 14 天、RCA/Evaluation 90 天及 Ground Truth 至场景废弃的默认保留。
5. `03-WP08.T05` 实现 `DELETE_PENDING → 对象删除 → DELETED`，删除前检查保留和引用保护；失败可重入。
6. `03-WP08.T06` 实现对象无元数据、元数据无对象、哈希漂移的孤儿对账及告警，不自动删除受保护数据。

**验证：**路径/越权/哈希/超限/引用删除负向测试通过；生命周期时钟测试覆盖到期、重试和 Ground Truth 永久保留。

### 03-WP09：持久任务、租约恢复与 SSE 重放

**输入：**第 4.6、6.10、10.1 节。

**代码/配置落点：**task Repository/worker、租约恢复、SSE event Repository/projector。

**任务：**

1. `03-WP09.T01` 使用 `FOR UPDATE SKIP LOCKED` 按状态/available_at/priority 领取任务，原子设置 lease owner/until。
2. `03-WP09.T02` 实现最大 attempts、过期租约回收、idempotency key 和已提交副作用检查；恢复不直接重置为初始状态。
3. `03-WP09.T03` 写入 SSE 事件时绑定 runId；按 `(runId,eventId)` 重放 `Last-Event-ID` 之后的已提交事件。
4. `03-WP09.T04` 验证跨 Incident/Run 的 Last-Event-ID 不泄漏事件；projector 失败仅重试 outbox 并告警。

**验证：**并发 worker 不重复执行；进程中止后租约到期可恢复；SSE 只重放目标 Run 且顺序稳定。

### 03-WP10：Migration 与数据库交付证据

**输入：**03-WP01–09 和阶段 01 版本锁。

**代码/配置落点：**Testcontainers 套件、Migration 兼容报告、Schema/权限快照。

**任务：**

1. `03-WP10.T01` 每条 migration 测空库和受支持生产前一版本升级，验证 checksum、顺序和向后兼容。
2. `03-WP10.T02` 验证新 Schema 上旧应用最低回归、全部角色权限、并发不变量和回滚行为。
3. `03-WP10.T03` 扫描删除/重命名/改类型/收紧非空等破坏性 DDL；本阶段不得引入未经 ADR 的 contract cleanup。
4. `03-WP10.T04` 发布 pgvector extversion、Schema digest、Migration digest、测试报告和角色矩阵证据。

**验证：**锁定 PostgreSQL/pgvector 镜像下所有套件通过；H2、Hibernate 建表或手工未版本化 DDL 检查必须为零。

## 阶段内执行顺序

```text
03-WP01 ─→ 03-WP02 ─→ 03-WP03 ─→ 03-WP04
                  ├─→ 03-WP05
                  ├─→ 03-WP06 ─→ 03-WP07
                  └─→ 03-WP08
03-WP05 ─→ 03-WP09
全部通过 ─→ 03-WP10
```

## 测试与证据矩阵

| 验证层 | 必测内容 | 失败条件 | 证据 |
|---|---|---|---|
| Migration | 空库、前版升级、扩展/Schema | 自动建表、未版本化 DDL、破坏性变更 | Flyway/Testcontainers 报告 |
| 权限 | 四 schema、所有运行角色、RLS | Ground Truth/跨 Agent/领域表越权成功 | 角色矩阵、拒绝 SQL 结果 |
| 一致性 | Run/attempt 并发、CAS、outbox | 双提交、覆盖写、部分事务 | 并发/事务测试报告 |
| 代码/RCA | Code Source/部署 revision/CodeSnapshot、Hypothesis 关系、封账/RCA | revision 歧义、provenance 丢失、封账后写入、重复 RCA | Repository/权限/封账集成报告 |
| 向量 | revision、维度、精确过滤/排序 | 跨 revision、非法向量、默认 ANN | pgvector 集成与 Schema 报告 |
| Artifact | 路径、哈希、权限、生命周期 | 逃逸、越权、受保护删除 | 安全测试、对账报告 |
| 恢复 | task lease、SSE replay | 重复副作用、跨 Run 泄漏 | worker/SSE 集成报告 |

## 主要输出

- `opspilot-adapters/persistence-postgres` 与 Artifact 存储 Adapter；
- 全部首版 Flyway、角色/授权脚本和迁移证据；
- JPA/JDBC Repository、UnitOfWork、封账写保护、outbox publisher/projector；
- Code Source/Repository/Resource Binding/CodeSnapshot、`code_analysis_scope` 只读视图和 Hypothesis/RCA 结构化持久化；
- pgvector 精确检索 Repository，以及文档/Chunk/revision/checkpoint 的持久化与原子切换原语；
- Artifact 保存、读取、校验、保留、删除和对账任务；
- PostgreSQL/pgvector Testcontainers 集成测试。

## 完成门禁

- 空库、前版升级、权限、回滚和 pgvector 扩展检查通过；扩展缺失时 readiness DOWN，不退化到内存向量库。
- 两个并发 Run 创建只有一个提交，另一个返回可映射为 `409 INCIDENT_ACTIVE_RUN_EXISTS` 的冲突。
- 状态、转换、审计和 outbox 原子提交；CAS 冲突不会覆盖；任务租约过期可恢复且不重复副作用。
- `agent_state.version` 与快照内版本一致，必需引用存在且属于同一 Run；AgentScope state 按 `(server_agent_id,user_id,session_id)` 隔离，continuation 复用原会话而新 attempt 使用新会话，保存失败不切换内存/文件 Store。
- migrator、app、Sample、Fault Lab、Evaluation 和各专业 Agent 的数据库访问严格符合四 schema 所有权；Ground Truth 只对 `fault_lab_role`/`evaluation_role` 可见。
- Code Agent 只能读取 `code_analysis_scope` 最小视图且无法读取底表；Resource/image digest 能解析到唯一 repository/完整 commit，CodeSnapshot 与代码 Evidence provenance 可回溯。
- Hypothesis 支持/冲突 Evidence 和验证结果结构化入库；`analysis_sealed_at` 后拒绝当前 Run 新分析事实，且一个 Run 只能保存一份最终 RCA 元数据。
- 向量维度漂移、NaN/Infinity、COSINE 零向量和跨 revision 混用均被拒绝。
- revision 只有在覆盖状态证明完整后才能通过事务原语原子切换；失败或回滚继续使用旧 active revision。
- Artifact 路径穿越、符号链接逃逸、哈希错误、越权与引用保护删除测试通过。
- 默认保留期、`DELETE_PENDING/DELETED` 状态、对象/元数据孤儿对账和 Ground Truth 保留规则测试通过。

## 明确不做

- 不加入 Redis、外部向量库或通用对象存储；生产 Artifact 介质仍是待确认项。
- 不在没有代表性基准时创建 HNSW/IVFFlat；需要时按第 9.6 节另行基准和 admin job。
- 不在本阶段实现 Provider 驱动的文档导入、更新、删除、质量验证或旁路重向量化编排；完整知识链路属于阶段 05。

## 设计依据

- [数据与向量存储](../design/opspilot-system-design/05-data-and-vector-storage.md)
- [可靠性、安全与可观测性](../design/opspilot-system-design/08-reliability-security-and-observability.md)
- [实现合同与演进规则](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
