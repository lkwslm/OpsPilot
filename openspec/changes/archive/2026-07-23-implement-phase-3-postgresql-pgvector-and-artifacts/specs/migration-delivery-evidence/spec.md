## ADDED Requirements

### Requirement: 每条 migration 的双路径验证
每条 Flyway migration MUST 在阶段 01 锁定的 PostgreSQL/pgvector 镜像上验证空库安装与受支持生产前一版本升级，并校验 checksum、顺序、扩展版本和向后兼容；MUST NOT 使用 H2 或 Hibernate 建表替代。

#### Scenario: 空库与前版升级
- **GIVEN** 一个空数据库和一个来源可追溯的生产前一版本 schema 样本
- **WHEN** 分别应用本阶段全部 migration
- **THEN** 两条路径都达到相同预期 schema digest，checksum、顺序与 pgvector extversion 符合锁定值

### Requirement: 新 Schema 的旧应用最低回归
系统 MUST 在新 schema 上运行生产前一版本应用的最低回归，并验证全部角色权限、并发不变量和事务回滚；未经 ADR 的删除、重命名、改类型或收紧非空等破坏性 DDL MUST 被阻断。

#### Scenario: 破坏性 DDL 扫描
- **GIVEN** 候选 migration 包含删除列、重命名、非兼容类型修改或直接收紧非空
- **WHEN** 运行 migration 兼容性门禁且不存在批准的 ADR
- **THEN** 构建失败，migration 不进入交付基线

### Requirement: 数据库交付证据必须可复现
交付 MUST 发布 pgvector extversion、schema digest、migration digest、Testcontainers 报告和完整角色矩阵证据，并记录镜像 digest 与生产前版来源；任一根门禁失败 MUST 阻止阶段 03 标记完成。

#### Scenario: 证据包完整性检查
- **GIVEN** 阶段 03 候选交付
- **WHEN** 验证证据清单与其 digest
- **THEN** 所有要求的版本、快照、报告和权限结果均可定位并可在锁定镜像上复现，缺项使门禁失败

### Requirement: readiness 不得静默降级
应用启动时 MUST 校验 Flyway 版本与 pgvector `extversion`；缺失或不匹配时 readiness MUST 为 DOWN，系统 MUST NOT 退化到内存向量库、文件 Store 或自动建表模式。

#### Scenario: pgvector 扩展缺失
- **GIVEN** 数据库可连接但 `vector` 扩展缺失或版本不匹配
- **WHEN** 应用执行启动检查
- **THEN** readiness 保持 DOWN，不接收流量且不注册任何持久化回退实现
