## 背景与动机

阶段 02 已冻结核心状态、Port、事务边界和身份合同，但系统尚无可部署的 PostgreSQL 业务事实源、pgvector 知识存储及受控 Artifact 存储。阶段 03 需要把这些合同落实为可迁移、可隔离、可恢复且有交付证据的持久化实现，为后续 Provider、Agent 和完整知识链路提供可信基础。

## 变更内容

- 使用锁定版本的 PostgreSQL/pgvector 和 Flyway 建立四个 schema、完整首版领域表、关系索引、约束与非敏感种子配置，并在启动时校验 migration 和扩展版本。
- 建立 migrator、Server/app、Sample、Fault Lab、Evaluation 及五个专业 Agent 的最小权限角色，通过授权、RLS 或受控 Repository 谓词隔离 A2A 行与 Ground Truth。
- 为常规 CRUD 提供不泄漏 JPA Entity 的 Repository，为向量距离和 Top-K 提供受 allowlist 控制的 JDBC/jOOQ 查询，并实现真实 PostgreSQL UnitOfWork、CAS、事务 outbox 和分析封账写保护。
- 按 revision 合同持久化 pgvector，校验维度、度量、归一化、有限值与 COSINE 非零范数；在少于 50,000 个 active Chunk 的 MVP 基线下执行过滤后的精确检索，不默认创建 ANN 索引。
- 提供知识文档版本、Chunk、导入 checkpoint、内容哈希复用、覆盖状态、active revision/searchable 原子切换和回滚窗口的持久化原语，但不编排 Provider 调用。
- 实现 `ArtifactAccessService` 与受控本地卷 Adapter，覆盖原子写入、SHA-256、访问控制、不可覆盖元数据、默认保留期、引用保护、两阶段删除和孤儿对账。
- 使用 PostgreSQL `FOR UPDATE SKIP LOCKED` 实现持久任务租约与恢复，并按 Run 支持 SSE `Last-Event-ID` 的已提交事件重放。
- 为每条 migration、角色权限、并发不变量、事务回滚、pgvector、Artifact 生命周期和恢复语义建立 Testcontainers 证据；禁止 H2、Hibernate 自动建表和未版本化 DDL。
- 不引入 Redis、外部向量库、通用对象存储、未经基准验证的 HNSW/IVFFlat，也不实现 Provider 驱动的知识导入或重向量化编排。

## 能力范围

### 新增能力

- `relational-domain-persistence`：完整领域表、结构化关系、关键列、关系索引、生命周期约束、JPA/JDBC Repository 与稳定冲突映射。
- `database-role-isolation`：四 schema 所有权、运行角色最小授权、专业 Agent 行隔离、Code Agent 最小视图及 Ground Truth 隔离。
- `pgvector-revision-retrieval`：revision 不变量、向量合法性、受控运算符和过滤后精确 Top-K 检索。
- `knowledge-version-persistence`：文档/Chunk/job checkpoint、同 revision 内容复用、覆盖状态、active revision 原子切换与回滚原语。
- `artifact-lifecycle-storage`：受控本地卷 Artifact 的原子写读、完整性验证、访问级别、保留、删除和孤儿对账。
- `durable-task-sse-replay`：`SKIP LOCKED` 任务租约恢复、幂等副作用检查及按 Run 隔离的 SSE 重放。
- `migration-delivery-evidence`：空库与前版升级、旧应用最低回归、破坏性 DDL 扫描以及 schema/migration/权限证据发布。

### 修改能力

- `postgres-boundary-gate`：将阶段 0 的最小边界扩展为阶段 03 完整 Flyway 基线、角色预置、扩展/readiness 校验和生产前版升级门禁。
- `transactional-checkpoint-outbox`：将测试合同落实到 PostgreSQL UnitOfWork，并补充结果接收原子事务、分析封账保护和真实 outbox publisher/projector 要求。
- `incident-agent-state-contract`：补充数据库列 version 与 JSON 内 version 一致性、引用归属校验及 PostgreSQL CAS 恢复要求。

## 影响范围

- 主要代码与配置：`deployment/postgres/init/`、`opspilot-adapters/persistence-postgres`、Artifact 存储 Adapter、Server composition root 与 readiness 配置。
- 主要数据边界：`opspilot`、`opspilot_a2a`、`sample`、`opspilot_eval`，以及本地 Artifact 受控根目录。
- 主要依赖与运行环境：阶段 01 锁定的 PostgreSQL/pgvector 镜像、Flyway、JPA、受控 JDBC/jOOQ 与 Testcontainers。
- 对外协议继续以 `docs/design/contracts/` 为最高事实源；本变更不新增产品 Controller 或 Provider 协议，也不改变阶段 02 冻结的领域身份与状态所有权。
