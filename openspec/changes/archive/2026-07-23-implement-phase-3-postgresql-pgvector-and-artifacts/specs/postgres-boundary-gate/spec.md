## MODIFIED Requirements

### Requirement: 最小 PostgreSQL/pgvector 边界
系统 MUST（必须）使用阶段 01 锁定的 PostgreSQL/pgvector 镜像，由初始化脚本只创建数据库、登录角色与最小密码注入，由 `opspilot_migrator` 依次执行扩展、四个设计冻结 schema、V1–V7 migration、RLS/授权和可重复非敏感 seed；应用、Sample、Fault Lab、Evaluation 与专业 Agent 角色不得执行 DDL 或 `CREATE EXTENSION`，也不得以 H2 替代。

#### Scenario: 锁定容器建立完整数据库边界
- **GIVEN** 一个空的锁定 PostgreSQL/pgvector Testcontainers 实例和仅含 migrator DDL 权限的凭证集合
- **WHEN** 执行初始化与全部 Flyway migration
- **THEN** pgvector、`opspilot/opspilot_a2a/sample/opspilot_eval`、完整首版表与各角色权限均按冻结顺序建立，应用角色执行 DDL 失败且测试未启动 H2

## ADDED Requirements

### Requirement: Flyway 是唯一 DDL 入口
系统 MUST 固定 V1–V7 与可重复非敏感 seed 的命名、checksum 和执行顺序，MUST NOT 由应用启动逻辑临时改变 migration；Hibernate MUST 使用 `ddl-auto=validate`，任何手工未版本化 DDL MUST 使交付检查失败。

#### Scenario: 应用启动不能修复缺失表
- **GIVEN** 数据库缺失预期 migration 或对象
- **WHEN** 应用以生产 Profile 启动
- **THEN** Hibernate 不创建或修改对象，启动/readiness 失败并指明 migration 不一致

### Requirement: 扩展与 migration readiness 门禁
应用 MUST 在启动时检查 Flyway 版本和 pgvector `extversion`；任一缺失或不匹配时 readiness MUST 为 DOWN，应用 MUST NOT 接收流量或降级到其他 Store。

#### Scenario: migration 失败时阻断流量
- **GIVEN** Flyway migration 失败或 pgvector 版本与锁定值不符
- **WHEN** 编排平台探测应用 readiness
- **THEN** readiness 返回 DOWN，业务入口不接收流量且不存在内存或文件持久化回退
