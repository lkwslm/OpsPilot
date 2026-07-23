## 背景

阶段 02 已定义领域身份、四层状态机、窄 Port、`IncidentAgentState`、事务 checkpoint/outbox 和 composition root 边界，但生产代码尚未具备真实持久化实现。阶段 03 必须在阶段 01 锁定的 PostgreSQL/pgvector 镜像上建立唯一业务事实源，同时把 Ground Truth、专业 Agent 状态、Artifact 内容和业务元数据分隔在明确的信任边界内。

本设计覆盖 `docs/implementation-plan/03-postgresql-pgvector-and-artifacts.md` 的 03-WP01 至 03-WP10。合同事实源仍依次为 `docs/design/contracts/`、设计中的状态矩阵与不变量、阶段实施计划；数据库映射不得反向改变 core 领域语义。

实施依赖顺序如下：先完成数据库基线，再建立表与约束；角色隔离、Repository、向量和 Artifact 可在共同基线上推进；知识版本依赖向量合同，持久任务/SSE 依赖真实 UnitOfWork；最后统一产出 migration 与数据库交付证据。

## 目标与非目标

**目标：**

- 由 Flyway 唯一管理 PostgreSQL/pgvector DDL，支持空库和生产前一版本升级，并在 readiness 中暴露版本不匹配。
- 以数据库约束、角色授权和受控 Repository 同时落实状态、身份、并发、幂等、生命周期与隔离合同。
- 提供不泄漏 persistence Entity 的 JPA Repository、受控向量 JDBC/jOOQ 查询和真实 PostgreSQL UnitOfWork。
- 提供知识版本原子切换、Artifact 完整生命周期、持久任务租约和按 Run 的 SSE 重放原语。
- 通过锁定镜像上的 Testcontainers 套件生成可复现的 schema、权限、迁移、事务和安全证据。

**非目标：**

- 不实现 Embedding、Rerank 或其他 Provider 驱动的导入、更新、删除、质量验证和重向量化编排。
- 不引入 Redis、外部向量数据库或通用对象存储；生产 Artifact 介质选择留待后续决策。
- 不在缺少代表性基准时创建 HNSW/IVFFlat，也不建立动态 operator、维度或 SQL 文本扩展点。
- 不新增产品 Controller、A2A HTTP 传输或通用 Repository/DAO 框架，不改变阶段 02 的领域所有权。
- 不用 H2、Hibernate 自动建表、手工未版本化 DDL 或内存实现作为生产回退。

## 设计决策

### 1. 初始化脚本与 Flyway 职责严格分离

`deployment/postgres/init/` 只创建数据库、登录角色并接收最小密码注入；所有扩展、schema、表、约束、索引、视图、RLS、授权和默认权限均由 `opspilot_migrator` 执行。长期进程不持有 migrator 凭证，应用配置固定为 `ddl-auto=validate`。

Flyway 按冻结顺序组织 V1–V7：扩展/schema/权限基线、核心与 A2A 领域、Sample、模型与用量、知识与向量、Evaluation/Ground Truth 隔离、关系索引与最终授权；可重复 migration 仅维护非敏感 seed。具体文件可按现有模块命名规范拆分，但版本语义、checksum 和顺序不可由应用启动逻辑改写。

选择该方式是为了让数据库状态可审计并支持前版升级。备选的 Hibernate 建表或应用启动临时 DDL 无法提供稳定 checksum、权限收敛和兼容性证据，因此禁用。

### 2. 关系列承载不变量，JSONB 只承载版本化扩展内容

Incident/Run、task、A2A、Source、Repository、CodeSnapshot、Observation、Evidence、Hypothesis、RCA、调用审计、Approval、SSE、Artifact、Evaluation、模型/Prompt/Usage、知识版本与 Ground Truth 均建立明确职责表。核心状态使用 `varchar + CHECK`；身份、状态、唯一键、外键、高频过滤字段和金额/时间等约束不得只存在 JSONB。

JSONB 必须包含受支持的 `schemaVersion`，由数据库轻量约束和映射层完整 Schema 校验共同保证。追加写审计、调用和状态转换不提供覆盖更新路径。该方案牺牲任意形态扩展的便利性，换取查询计划、约束和升级行为可验证。

### 3. 数据库约束是并发与幂等的最终裁决者

单 Incident 单活动 Run、单 step 单活动 attempt、单文档 ACTIVE 版本及 API/task/Tool/message/A2A 幂等通过唯一或部分唯一索引实现；活动集合严格排除 `COMPLETED/FAILED/CANCELLED`。外键按聚合生命周期选择 `RESTRICT` 或受控 `CASCADE`，Incident 删除不得清除审计、模型调用、状态转换和 Evaluation 结果。

Repository 捕获已命名约束的冲突并映射为稳定领域错误，例如 `409 INCIDENT_ACTIVE_RUN_EXISTS` 和幂等 hash 冲突。应用层预检查只改善错误体验，不能替代数据库裁决。

### 4. 角色隔离同时依赖授权边界与查询范围

`opspilot_app_role` 只供 Server/Supervisor 使用；五个专业 Agent 使用独立角色，不能写 Incident/Run/Hypothesis/RCA 底表。每个专业角色的 `opspilot_a2a` 与 Agent runtime state 以 `server_agent_id` 进行 RLS 隔离，或由受控 Repository 谓词限定并叠加角色级允许/拒绝测试，禁止专业 Agent 复用 app 角色。

Code Agent 只读取按 `runId + repositoryId` 限定的 `code_analysis_scope` 视图；零个或多个未消歧 commit 均 fail closed。`sample_app_role` 只访问 `sample`。只有 `fault_lab_role` 与 `evaluation_role` 可使用 `opspilot_eval`，其中 Evaluation 对业务 schema 只获得写 evaluation result 和必要 Artifact 元数据的权限。所有 schema 撤销 `PUBLIC` 默认访问，并为 future table 设置默认权限。

单靠应用谓词无法证明凭证泄漏后的隔离，单靠 RLS 又不能表达全部领域归属检查，因此采用数据库权限、行隔离、受控视图/Repository 和权限矩阵的组合。

### 5. JPA 与受控 JDBC/jOOQ 按查询性质分工

常规 CRUD 使用显式 JPA Entity/映射处理 JSONB、`timestamptz`、`decimal` 和乐观锁，Repository 在 adapter 内完成领域对象转换，core 不依赖 Entity。向量距离、Top-K、`SKIP LOCKED` 和需要 PostgreSQL 特性的查询使用固定模板的 JDBC/jOOQ。

所有动态维度、距离运算符、排序方向和状态集合必须先映射到封闭 allowlist；配置或请求文本不能直接拼入 SQL。相比把全部查询统一到 JPA，该分工保留常规映射效率，同时让 PostgreSQL 特性保持可读、可测且无动态注入面。

### 6. 真实 UnitOfWork 固化结果接收与封账边界

结果接收在单个 PostgreSQL UnitOfWork 中更新 Run/step/快照、A2A binding、调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox。`IncidentAgentStateRepository.compareAndSet` 同时校验表列 version、JSON 内 version 和同 Run 引用归属；冲突后必须重读并重新判断状态。

`analysis_sealed_at` 非空后，数据库保护与 Repository 检查共同拒绝该 Run 新增或修改 Evidence、Hypothesis 及其 Evidence/Verification 关系。报告重试只读取同一封账快照，新分析事实只能进入新 Run。outbox event 在提交前不可见，publisher/projector 以 `eventId` 幂等；提交后的投影失败只能重试并告警，不能回滚核心状态。

### 7. pgvector revision 使用无 typmod vector 与双重维度校验

向量列使用无 typmod `vector`，并通过 `(model_revision_id, embedding_dimension)` 复合外键和 `vector_dims` 同时约束真实维度。distance metric 与 normalization 是 revision 不可变属性；应用拒绝 NaN/Infinity，COSINE 向量在应用与数据库两层拒绝零范数。

查询只能把 COSINE、INNER_PRODUCT、L2 映射为固定 operator，并同时过滤 collection、active revision、`searchable=true`、文档状态和允许的元数据条件。query revision 与维度必须匹配。MVP 在少于 50,000 个 active Chunk 时使用过滤后的精确扫描，schema 中不创建 ANN；此选择优先保证过滤语义和 revision 正确性，性能扩展须另行基准和 admin job。

### 8. 知识切换以覆盖证明和单事务为边界

文档、版本、Chunk、ingestion job/checkpoint、失败 Chunk、attempts 和 `last_error` 均可持久恢复。相同 revision 的内容哈希可复用计算结果，但新 Chunk 必须插入独立向量行并再次校验哈希与维度；禁止跨 revision 复用。

只有 coverage 完整时，才能在一个事务中切换 document version/collection active revision，并同步设置新旧 `searchable`。`deleted_at` 在所有检索入口立即过滤；旧 revision 在保留窗口内可被原子恢复。阶段 03 只暴露 Repository、约束和事务原语，不负责调用 Provider。

### 9. Artifact 元数据与对象内容分别管理但保持可对账

业务表只保存 `artifactId`；Artifact 元数据记录 `storage_provider/object_key/uri/sha256/size_bytes/media_type/access_level/lifecycle_status/expires_at`。Adapter 将 `artifactId` 解析到受控根目录下的 object key，拒绝绝对路径、`..`、设备文件和符号链接逃逸。

写入流程为同目录临时文件、流式计算 SHA-256/大小/媒体类型、原子移动、登记不可覆盖元数据；更新内容必须创建新 Artifact。读取时验证调用方、Run/Task 归属、访问级别、大小和哈希，并对流量设置上限。

默认保留期为 Incident/Run/审计 30 天、原始观测 14 天、RCA/Evaluation 90 天，Ground Truth 保留到场景版本废弃。删除固定执行 `DELETE_PENDING → 删除对象 → DELETED`，并在删除前检查引用和保留；对账区分对象无元数据、元数据无对象和哈希漂移，告警但不自动删除受保护数据。

### 10. 任务租约与 SSE 只呈现已提交事实

worker 使用 `FOR UPDATE SKIP LOCKED` 按状态、`available_at`、priority 领取任务，并原子写入 lease owner/until。恢复逻辑检查最大 attempts、幂等键和已提交副作用；过期租约进入显式恢复判断，不能简单重置为初始状态。

SSE event 在同事务中绑定 `runId`，按 `(runId,eventId)` 查询 `Last-Event-ID` 之后的已提交事件。跨 Run 的 event ID 返回零结果或稳定拒绝，projector 失败仅重试 outbox 并告警。

### 11. composition root 与证据门禁显式化

Server composition root 显式装配 PostgreSQL Repository、UnitOfWork 与 Artifact Adapter；不存在内存或文件 Store 的生产回退。readiness 检查 Flyway 版本与 pgvector `extversion`，缺失或不匹配时为 DOWN。

每条 migration 都在锁定镜像上测试空库和受支持生产前一版本升级，并运行旧应用最低回归、角色权限矩阵、并发/回滚、向量、Artifact 和恢复套件。交付物包含 pgvector extversion、schema digest、migration digest、测试报告和角色矩阵。破坏性 DDL 必须由扫描阻断，除非先有独立 ADR 与兼容计划。

## 风险与权衡

- [首版表与约束数量较多，migration 容易出现隐式依赖] → 固定 V1–V7 职责和工作包顺序，每条 migration 同时跑空库与前版升级。
- [RLS、默认权限和多 DataSource 容易因新表漏授权] → 撤销 `PUBLIC`、设置 default privileges，并让权限矩阵覆盖 current/future table。
- [JPA 与 JDBC/jOOQ 混用可能产生事务边界分裂] → 两者必须加入同一 DataSource/transaction manager，故障注入验证全提交或全回滚。
- [无 typmod vector 把部分校验责任移到约束和应用] → 使用复合外键、`vector_dims`、有限值与零范数双校验，并为非法输入建立负向测试。
- [精确扫描在数据增长后延迟上升] → 固定少于 50,000 active Chunk 的适用门槛；超过门槛只触发独立基准与设计决策，不在本阶段静默建 ANN。
- [本地卷的元数据事务与文件原子移动不能形成单一数据库事务] → 使用临时写、内容校验、幂等状态和孤儿对账收敛失败窗口，不声称跨介质强事务。
- [封账数据库保护可能增加写路径复杂度] → 将受保护表和错误码集中定义，并以封账前后集成测试覆盖所有入口。
- [前版升级样本可能偏离真实生产状态] → 以已发布 schema/migration digest 构造基线，并发布测试使用的来源与版本。

## 迁移与回滚计划

1. 确认阶段 01 锁定的 PostgreSQL/pgvector 镜像 digest 与阶段 02 Port/事务合同均通过，冻结生产前一版本 schema 样本。
2. 由初始化脚本预置数据库和登录角色，以 migrator 执行 V1–V7 及可重复非敏感 seed；生成 checksum、schema 和权限快照。
3. 在新 schema 上运行旧应用最低回归，再部署使用新 Repository/UnitOfWork/Artifact Adapter 的应用；readiness 在 Flyway 或扩展不匹配时保持 DOWN。
4. 运行角色拒绝清单、并发不变量、checkpoint/outbox、pgvector、知识切换、Artifact、任务租约和 SSE 重放套件后才允许接收流量。
5. 应用部署失败时回滚应用版本，但不执行未经验证的 down migration；新 migration 必须在 expand/compatible 边界内让前一版应用满足最低回归。
6. 若 migration 本身在提交前失败，数据库事务回滚并保持旧版本；若发现已提交的破坏性问题，停止流量，按独立修复 migration 前滚。Artifact 删除和知识 revision 切换分别依靠状态机与旧 revision 保留窗口恢复。

## 待确认事项

- 生产环境 Artifact 介质及其一致性模型仍待确认；本阶段只交付本地卷 Adapter 与可替换的 core Port。
- active Chunk 超过 50,000 后是否采用 HNSW 或 IVFFlat，必须基于代表性过滤、召回和延迟基准另行决策。
- 支持的“生产前一版本”具体 release/schema digest 需在实施 03-WP10 前由发布基线确定；未确定时该工作包视为阻塞，不能以临时 schema 代替交付证据。
