## ADDED Requirements

### Requirement: 完整领域职责表与结构化关系
系统 MUST 使用独立关系表持久化 Incident/Run/task/API 幂等、Agent state、A2A binding 与状态历史、目标资源与拓扑、可观测和代码 Source、Repository/部署 revision/CodeSnapshot、Observation、Evidence/provenance、Hypothesis 关系、RCA 元数据、调用审计、ChainFailure、Approval、SSE、Artifact、Evaluation、模型/Prompt/Usage、知识版本及 Ground Truth；支持或冲突 Evidence 与 Verification 结果 MUST 使用可查询关系表达，源码正文 MUST NOT 进入数据库。

#### Scenario: Schema 职责快照比对
- **GIVEN** 阶段 03 的空库 migration 已完成
- **WHEN** 将 schema 快照与设计职责表逐项比对
- **THEN** 每类事实都有唯一职责表和明确归属外键，且不存在以自然语言、隐藏思考或源码正文替代的结构化关系

### Requirement: 关键不变量不得隐藏在 JSONB
核心状态 MUST 使用 `varchar + CHECK`；状态、唯一键、外键、高频过滤字段、哈希、时间和金额 MUST 使用关系列及数据库或映射约束。JSONB MUST 包含受支持的 `schemaVersion`，但 MUST NOT 成为关键不变量的唯一载体。

#### Scenario: 非法核心字段被拒绝
- **GIVEN** 候选记录包含未知核心状态、缺失归属外键、非法哈希、非法时间或非法金额
- **WHEN** Repository 尝试持久化记录
- **THEN** 数据库或映射层以稳定错误拒绝写入，且不会只因 JSONB 中存在同名字段而接受

### Requirement: 并发、幂等与活动生命周期约束
数据库 MUST 保证单 Incident 单活动 Run、单 step 单活动 attempt、单文档 ACTIVE 版本，以及 API、task、Tool、message 和 A2A 幂等；活动 Run 的定义 MUST 严格排除 `COMPLETED/FAILED/CANCELLED`，并为 Chunk ordinal、向量复合身份和 Source 配置身份建立唯一约束。

#### Scenario: 并发创建活动实体
- **GIVEN** 同一 Incident 或 step 当前没有活动 Run 或 attempt
- **WHEN** 两个事务并发创建同一作用域的活动记录
- **THEN** 只有一个事务提交，另一个得到可映射的稳定约束冲突，数据库中只有一个活动记录

#### Scenario: 同幂等键的请求重放与冲突
- **GIVEN** 一个幂等键已保存请求 hash 和结果
- **WHEN** 分别提交相同 hash 和不同 hash 的请求
- **THEN** 相同 hash 重放原结果，不同 hash 返回稳定冲突且不产生第二次副作用

### Requirement: 查询索引与保留型外键生命周期
系统 MUST 为 Run、task 领取、Evidence/Observation、Code Source/Repository/部署 revision/CodeSnapshot、Hypothesis 关系、单 Run RCA、SSE、模型调用和知识过滤建立关系索引；外键 MUST 明确选择 `RESTRICT` 或受控 `CASCADE`，Incident 删除 MUST NOT 级联删除审计、模型调用、状态转换或 Evaluation 结果。

#### Scenario: 查询与删除计划验证
- **GIVEN** 具有代表性关联数据的 PostgreSQL 测试库
- **WHEN** 执行高频查询计划并尝试删除被审计事实引用的 Incident
- **THEN** 查询使用预期关系索引，删除被引用 Incident 被拒绝或保留审计事实且不发生禁止的级联删除

### Requirement: Repository 技术分工与领域隔离
常规 CRUD MUST 使用显式 JPA 映射处理 JSONB、`timestamptz`、`decimal` 与乐观锁，向量距离、Top-K 和 PostgreSQL 专用并发查询 MUST 使用受控 JDBC/jOOQ；persistence Entity MUST NOT 暴露到 core，任何动态维度、运算符或排序片段 MUST 来自已验证 allowlist。

#### Scenario: Repository 边界与 SQL 注入负向检查
- **GIVEN** core 编译边界和包含非法 operator、维度或排序文本的查询输入
- **WHEN** 执行架构测试与 Repository 负向测试
- **THEN** core 不依赖 JPA Entity，非法动态值在 SQL 执行前被拒绝且配置文本未直接拼入 SQL

### Requirement: 数据库冲突稳定映射
Repository MUST 通过已命名约束将数据库冲突映射为稳定领域错误，至少包括 `409 INCIDENT_ACTIVE_RUN_EXISTS` 和幂等 hash 冲突，不得向 core 暴露厂商异常文本。

#### Scenario: 活动 Run 唯一约束冲突
- **GIVEN** 一个 Incident 已存在活动 Run
- **WHEN** Repository 再次创建活动 Run并触发对应唯一约束
- **THEN** 调用方得到 `409 INCIDENT_ACTIVE_RUN_EXISTS`，且响应不包含 PostgreSQL 驱动异常或 SQL 文本
