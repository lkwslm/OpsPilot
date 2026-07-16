# 阶段 03：PostgreSQL/pgvector、领域持久化与 Artifact

## 目标

完成 PostgreSQL 唯一业务事实源、pgvector 知识存储、四 schema/角色隔离、事务 outbox、来源追溯和 Artifact 存储 Adapter，并在 composition root 显式装配 Repository。

## 前置门禁

- core 的状态、Port、事务边界和冻结 ID 已通过阶段 02。
- 使用阶段 01 锁定的 PostgreSQL/pgvector 版本和镜像 digest。

## 实施内容

1. 按顺序实现 Flyway：扩展/schema、核心 Incident、Sample、模型与用量、知识/向量、Evaluation 隔离、关系索引和非敏感种子配置。
2. 建立 `opspilot`、`opspilot_a2a`、`sample`、`opspilot_eval`，以及 `opspilot_migrator`、`opspilot_app_role`、`sample_app_role`、`fault_lab_role`、`evaluation_role` 和各专业 Agent 角色；只有 Fault Lab/Evaluation 角色可访问 `opspilot_eval`，Agent/app/Tool 数据源和模型上下文无该 schema 的 `USAGE`/`SELECT`，专业 Agent 无 Supervisor 领域表写权限。每个专业角色只能访问自己的 `opspilot_a2a` 行，使用 RLS，或使用按 `server_agent_id` 的受控 Repository 谓词并叠加数据库角色测试双重保证；专业 Agent 不使用 `opspilot_app_role`。
3. 实现第 10.1–10.5 节全部职责表，包括 task 租约、API 幂等、Agent state、A2A binding、状态历史、目标资源/拓扑、Source、Observation、Evidence/provenance、Hypothesis、调用审计、ChainFailure、Approval、SSE、Artifact、Evaluation、模型/Prompt/Usage 和知识版本表。
4. 用数据库约束落实单活动 Run、单活动 attempt、单活动文档版本、API/Tool/message/A2A 幂等、核心状态 CHECK、外键生命周期和追加写审计；数据库枚举统一使用 `varchar + CHECK`，高频查询字段、唯一约束、外键和状态不得只藏在 JSONB。
5. 实现 Repository/DAO：常规 CRUD 使用 JPA；向量距离/Top-K 使用受控 JDBC/jOOQ；所有动态维度和运算符来自已验证值与 allowlist。
6. 实现 pgvector revision 合同：真实维度、度量、归一化、有限值、COSINE 非零范数、复合外键和 `vector_dims` 双校验。MVP 小于 50,000 active Chunk 时使用过滤后精确扫描，不默认创建 ANN。
7. 实现基础知识版本的持久化能力：文档版本、Chunk、导入 checkpoint、同 revision 内容哈希复用、revision 覆盖状态、active revision/searchable 原子切换和回滚窗口；本阶段只提供 Repository、约束和事务原语，不调用 Embedding/Rerank，也不编排导入或重向量化流程。
8. 实现 ArtifactAccessService 和本地卷 Adapter：临时写入、SHA-256、原子移动、访问级别、受控路径、生命周期、保留/删除/对账；业务只持有 `artifactId`，内容寻址元数据不可覆盖，更新必须生成新 Artifact。首版本地默认保留 Incident/Run/审计 30 天、原始观测 Artifact 14 天、RCA/Evaluation 90 天，Ground Truth 永久保留到场景版本废弃；删除必须按 `DELETE_PENDING → 删除对象 → DELETED` 执行并通过引用保护与孤儿对账。
9. 实现状态/审计/outbox 的同事务持久化、PostgreSQL task `SKIP LOCKED` 租约恢复、SSE `Last-Event-ID` 的按 Run 重放。
10. 为每条 Flyway migration 建立空库与生产前一版本升级 Testcontainers 测试，禁止 Hibernate 自动建表和 H2 替代。

## 主要输出

- `opspilot-adapters/persistence-postgres` 与 Artifact 存储 Adapter；
- 全部首版 Flyway、角色/授权脚本和迁移证据；
- JPA/JDBC Repository、UnitOfWork、outbox publisher/projector；
- pgvector 精确检索 Repository，以及文档/Chunk/revision/checkpoint 的持久化与原子切换原语；
- Artifact 保存、读取、校验、保留、删除和对账任务；
- PostgreSQL/pgvector Testcontainers 集成测试。

## 完成门禁

- 空库、前版升级、权限、回滚和 pgvector 扩展检查通过；扩展缺失时 readiness DOWN，不退化到内存向量库。
- 两个并发 Run 创建只有一个提交，另一个返回可映射为 `409 INCIDENT_ACTIVE_RUN_EXISTS` 的冲突。
- 状态、转换、审计和 outbox 原子提交；CAS 冲突不会覆盖；任务租约过期可恢复且不重复副作用。
- `agent_state.version` 与快照内版本一致，必需引用存在且属于同一 Run；AgentScope state 按 `(server_agent_id,user_id,session_id)` 隔离，continuation 复用原会话而新 attempt 使用新会话，保存失败不切换内存/文件 Store。
- migrator、app、Sample、Fault Lab、Evaluation 和各专业 Agent 的数据库访问严格符合四 schema 所有权；Ground Truth 只对 `fault_lab_role`/`evaluation_role` 可见。
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
