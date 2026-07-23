## 1. 数据库启动、Flyway 基线与角色预置（03-WP01）

- [x] 1.1 `03-WP01.T01` 在 `deployment/postgres/init/` 将初始化脚本限制为创建数据库、登录角色和最小密码注入，确保只有 `opspilot_migrator` 能执行 DDL/扩展；以 app 角色执行 DDL 失败的 Testcontainers 结果验收。
- [x] 1.2 `03-WP01.T02` 在 `opspilot-adapters/persistence-postgres` 按 V1–V7 与可重复非敏感 seed 建立 Flyway 文件骨架和命名/checksum 规则；以 `flyway info/validate` 顺序与 checksum 证据验收。
- [x] 1.3 `03-WP01.T03` 在基线 migration 创建 `vector` 扩展及 `opspilot/opspilot_a2a/sample/opspilot_eval`，撤销 `PUBLIC` 并设置 future table 默认权限；以 schema/extension/default privileges 快照验收。
- [x] 1.4 `03-WP01.T04` 预置 app、Sample、Fault Lab、Evaluation 和五个专业 Agent 角色，拆分 migrator 与长期进程凭证；以角色连接与 `rolcreaterole/rolsuper/rolcreatedb` 拒绝矩阵验收。
- [x] 1.5 `03-WP01.T05` 将生产 JPA 配置设为 `ddl-auto=validate`，在 Server readiness 检查 Flyway 版本与 pgvector `extversion`；以缺失 migration/扩展时 readiness DOWN 且不接流量的集成测试验收。

## 2. 领域表、关键列与数据库不变量（03-WP02，依赖 03-WP01）

- [x] 2.1 `03-WP02.T01` 在核心 migration 与 JPA 映射建立 Incident、Run、task、api_idempotency、agent_state、endpoint、A2A binding 和 state_transition 表，核心状态统一为 `varchar + CHECK`；以未知状态与缺失归属写入失败验收。
- [x] 2.2 `03-WP02.T02` 建立 Target System/Resource/Relation、Observability Source、Code Source/Repository、Resource-Code Binding、CodeSnapshot、ObservationBatch/Record、Evidence/provenance 表及归属外键，禁止源码正文入库；以 schema 职责快照和非法归属负向测试验收。
- [x] 2.3 `03-WP02.T03` 建立 Hypothesis、Hypothesis-Evidence、Hypothesis-Verification、RCA Report、Tool/Model 调用、ChainFailure、Approval、SSE、Artifact、Evaluation 结果表，将支持/冲突/验证建模为结构化关系并对审计/调用/转换采用追加写语义；以更新拒绝和关系查询测试验收。
- [x] 2.4 `03-WP02.T04` 在 `opspilot_a2a` 建立 Task、Message、Artifact、Event 与 Agent runtime state 表，所有键包含 `server_agent_id` 且不与 Supervisor 快照混表；以跨平面外键/表归属检查验收。
- [x] 2.5 `03-WP02.T05` 建立 `sample` 订单/库存、模型/Profile/Prompt/Pricing/Usage、知识文档/版本/Chunk/revision/job 与 Ground Truth 表；以完整职责清单和四 schema 快照验收。
- [x] 2.6 `03-WP02.T06` 完成 Artifact Entity/映射，强制 `storage_provider/object_key/uri/sha256/size_bytes/media_type/access_level/lifecycle_status/expires_at`，并让业务表只保存 `artifactId`；以架构扫描与非法元数据写入测试验收。
- [x] 2.7 `03-WP02.T07` 为所有 JSONB 建立 `schemaVersion` 数据库规则与映射层校验，迁出 JSONB 中的状态、唯一键、外键和高频过滤字段；以 schema 扫描及未知版本负向测试验收。

## 3. 并发、幂等、索引与生命周期约束（03-WP03，依赖 03-WP02）

- [x] 3.1 `03-WP03.T01` 创建单 Incident 单活动 Run 的部分唯一索引，活动集合严格复用非 `COMPLETED/FAILED/CANCELLED` 定义；以双事务并发测试证明仅一个提交。
- [x] 3.2 `03-WP03.T02` 创建单 step 单活动 attempt，以及 messageId、远端 Task、API、task、Tool 幂等唯一约束；以同键同 hash 重放、不同 hash 冲突和并发 attempt 测试验收。
- [x] 3.3 `03-WP03.T03` 创建单文档 ACTIVE 版本、Chunk ordinal、向量复合身份和 Source 配置身份约束；以重复插入和并发激活负向测试验收。
- [x] 3.4 `03-WP03.T04` 为 Run、task 领取、Evidence/Observation、Code Source/Repository/部署 revision/CodeSnapshot、Hypothesis 关系、单 Run RCA、SSE、模型调用和知识过滤创建关系索引；以代表性 `EXPLAIN` 计划证据验收。
- [x] 3.5 `03-WP03.T05` 为每个外键明确 `RESTRICT/CASCADE` 生命周期，阻止 Incident 级联删除审计、模型调用、状态转换和 Evaluation 结果；以删除矩阵集成测试验收。
- [x] 3.6 `03-WP03.T06` 按约束名把 PostgreSQL 冲突映射为稳定领域错误，至少覆盖 `409 INCIDENT_ACTIVE_RUN_EXISTS` 和幂等 hash 冲突；以不泄漏 SQL/驱动异常的 Repository 测试验收。

## 4. 数据库角色、RLS 与 Ground Truth 隔离（03-WP04，依赖 03-WP03）

- [x] 4.1 `03-WP04.T01` 收敛 `opspilot_app_role` DML，并创建按 `runId + repositoryId` 限定的 `code_analysis_scope` 视图；禁止专业 Agent 写 Supervisor 表、Code Agent 读底表，零行或多 commit 均 fail closed，以角色 SQL 测试验收。
- [x] 4.2 `03-WP04.T02` 对专业角色的 A2A/AgentState 应用按 `server_agent_id` 的 RLS，或受控 Repository 谓词加角色测试的双重隔离；以跨 Agent 查询/更新零行或拒绝及审计证据验收。
- [x] 4.3 `03-WP04.T03` 将 `sample_app_role` 限制到 `sample`，为 Fault Lab 只授予故障编排最小权限且不赋予 Agent 身份；以允许/拒绝 SQL 清单验收。
- [x] 4.4 `03-WP04.T04` 只允许 `fault_lab_role/evaluation_role` 访问 `opspilot_eval`，并将 Evaluation 在业务 schema 的权限限制为写 evaluation result 与必要 Artifact 元数据；以双向越权测试验收。
- [x] 4.5 `03-WP04.T05` 验证 app、Agent、Tool DataSource 和 model Context Builder 对 Ground Truth 无 schema `USAGE`、表 `SELECT` 与卷路径权限；以数据库和文件系统全链路拒绝证据验收。
- [x] 4.6 `03-WP04.T06` 建立所有角色的允许/拒绝 SQL 权限矩阵，覆盖当前表与 future table 默认权限；以锁定镜像上的完整矩阵报告验收。

## 5. Repository、UnitOfWork 与持久 checkpoint（03-WP05，依赖 03-WP03/04）

- [x] 5.1 `03-WP05.T01` 实现常规 CRUD 的 JPA Entity/Repository 映射，显式处理 JSONB、`timestamptz`、`decimal` 和乐观锁，并在 adapter 内转换领域对象；以 core 不依赖 Entity 的架构测试和 CRUD 集成测试验收。
- [x] 5.2 `03-WP05.T02` 实现 `IncidentAgentStateRepository.compareAndSet`，同时校验表列 version、JSON 内 version 和必需引用的同 Run 归属；以版本不一致、跨 Run 引用和并发 CAS 测试验收。
- [x] 5.3 `03-WP05.T03` 实现结果接收 PostgreSQL UnitOfWork，在同事务更新 Run/step/快照、A2A binding、调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox；以每个写点的故障注入验证全提交或全回滚。
- [x] 5.4 `03-WP05.T04` 对 `analysis_sealed_at` 后的 Evidence、Hypothesis、Hypothesis-Evidence、Hypothesis-Verification 建立数据库与 Repository 写保护，并让报告重试只读封账快照；以所有入口的封账前后集成测试验收。
- [x] 5.5 `03-WP05.T05` 实现基于 `eventId` 的 outbox publisher/projector 幂等与提交后可见性，投影失败保持可重试；以提交前不可见、重复消费不重复投影测试验收。
- [x] 5.6 `03-WP05.T06` 在 checkpoint 每个关键写点建立故障注入套件，验证事务无残留、CAS 冲突会重读并重新判定状态；以 PostgreSQL 集成测试报告验收。

## 6. pgvector revision 与精确检索 Repository（03-WP06，依赖 03-WP03）

- [x] 6.1 `03-WP06.T01` 在向量 migration 使用无 typmod `vector`，建立 `(model_revision_id, embedding_dimension)` 复合外键和 `vector_dims` 双校验；以匹配与漂移维度写入测试验收。
- [x] 6.2 `03-WP06.T02` 在应用层拒绝 NaN/Infinity，并为 COSINE 在应用和数据库约束/触发器两层拒绝零范数；以绕过应用的直接 SQL 负向测试验收。
- [x] 6.3 `03-WP06.T03` 将 distance metric/normalization 固化为 revision 不可变属性，把 COSINE/INNER_PRODUCT/L2 映射为封闭 operator allowlist；以属性变更和非法 operator 测试验收。
- [x] 6.4 `03-WP06.T04` 用受控 JDBC/jOOQ 实现 collection、active revision、`searchable=true`、文档状态和元数据过滤后的精确 Top-K，并校验 query revision/维度；以固定数据集排序和跨 revision 负向测试验收。
- [x] 6.5 `03-WP06.T05` 保持少于 50,000 active Chunk 的精确扫描基线，确保默认 migration 无 HNSW/IVFFlat；以 schema 索引扫描和精确检索基线报告验收。
- [x] 6.6 `03-WP06.T06` 为 SQL 参数、动态维度和 operator 注入建立负向测试，阻止配置文本直接拼 SQL；以数据库未收到恶意语句和稳定拒绝结果验收。

## 7. 知识版本与原子切换持久原语（03-WP07，依赖 03-WP06）

- [x] 7.1 `03-WP07.T01` 实现 Knowledge Document/Version/Chunk 与 ingestion job/checkpoint 的创建、读取和追加更新 Repository；以中断后恢复固定 checkpoint 的集成测试验收。
- [x] 7.2 `03-WP07.T02` 实现相同 revision 的内容哈希复用，为新 Chunk 插入独立向量行并重新校验哈希/维度，禁止跨 revision 复用；以同 revision 成功和跨 revision 拒绝测试验收。
- [x] 7.3 `03-WP07.T03` 持久化 coverage、失败 Chunk、job attempts 和 `last_error`，确保失败不静默丢 Chunk；以部分失败后查询与恢复测试验收。
- [x] 7.4 `03-WP07.T04` 在单事务切换 document version/collection active revision 与新旧 `searchable`，以 coverage 完整为前置门禁；以不完整拒绝和中途故障保持旧 revision 测试验收。
- [x] 7.5 `03-WP07.T05` 实现 `deleted_at` 立即过滤、旧 revision 保留窗口与原子回滚原语，并用架构测试证明未引入 Provider 编排；以回滚后固定查询恢复旧结果验收。

## 8. ArtifactAccessService 与生命周期（03-WP08，依赖 03-WP03）

- [x] 8.1 `03-WP08.T01` 实现受控根目录和 `artifactId → storageProvider/objectKey` 解析，拒绝绝对路径、`..`、设备文件和符号链接逃逸；以路径安全负向套件验收。
- [x] 8.2 `03-WP08.T02` 实现临时写、流式 SHA-256/大小/媒体类型计算、同目录原子移动和元数据登记，禁止覆盖同 `artifactId` 内容寻址元数据；以 I/O 故障和覆盖负向测试验收。
- [x] 8.3 `03-WP08.T03` 实现读取时的访问级别、Run/Task 权限、哈希、大小校验和大文件受控流上限；以越权、漂移和超限测试验收。
- [x] 8.4 `03-WP08.T04` 配置 Incident/Run/审计 30 天、原始观测 14 天、RCA/Evaluation 90 天及 Ground Truth 至场景废弃的默认保留；以可注入时钟的边界测试验收。
- [x] 8.5 `03-WP08.T05` 实现引用保护的 `DELETE_PENDING → 对象删除 → DELETED` 状态机和可重入失败恢复；以引用删除拒绝、对象失败重试和最终状态测试验收。
- [x] 8.6 `03-WP08.T06` 实现对象无元数据、元数据无对象和哈希漂移的孤儿对账与告警，禁止自动删除受保护数据；以三类不一致固定数据集验收。

## 9. 持久任务、租约恢复与 SSE 重放（03-WP09，依赖 03-WP05）

- [x] 9.1 `03-WP09.T01` 使用 `FOR UPDATE SKIP LOCKED` 按状态、`available_at`、priority 领取任务并原子设置 lease owner/until；以多 worker 并发测试证明不重复领取与执行。
- [x] 9.2 `03-WP09.T02` 实现最大 attempts、过期租约回收、idempotency key 与已提交副作用检查，禁止把恢复任务直接重置为初始状态；以副作用后中止和租约到期恢复测试验收。
- [x] 9.3 `03-WP09.T03` 持久化绑定 `runId` 的 SSE event，并按 `(runId,eventId)` 重放 `Last-Event-ID` 后的已提交事件；以断线续传顺序测试验收。
- [x] 9.4 `03-WP09.T04` 阻止跨 Incident/Run 的 `Last-Event-ID` 泄漏，让 projector 失败仅重试 outbox 并告警；以跨 Run 负向测试和投影故障测试验收。

## 10. Migration 与数据库交付证据（03-WP10，依赖 03-WP01–09）

- [x] 10.1 `03-WP10.T01` 为每条 migration 建立锁定镜像上的空库与受支持生产前一版本升级 Testcontainers 测试，校验 checksum、顺序和向后兼容；以两条路径的相同预期 schema digest 验收。
- [x] 10.2 `03-WP10.T02` 在新 schema 上运行旧应用最低回归、全部角色权限、并发不变量和回滚行为套件；以版本来源可追溯的综合报告验收。
- [x] 10.3 `03-WP10.T03` 扫描删除、重命名、改类型、收紧非空等破坏性 DDL，阻止未经 ADR 的 contract cleanup，并检查 H2、Hibernate 建表和未版本化 DDL 为零；以构建门禁结果验收。
- [x] 10.4 `03-WP10.T04` 发布 pgvector extversion、PostgreSQL/pgvector 镜像 digest、schema digest、migration digest、测试报告和角色矩阵证据；以证据清单完整且可在锁定环境复现作为阶段 03 完成门禁。
