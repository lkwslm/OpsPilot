# postgres-boundary-gate Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for postgres-boundary-gate.

## Requirements

### Requirement: 最小 PostgreSQL/pgvector 边界
系统 MUST（必须）使用锁定的 PostgreSQL/pgvector 创建扩展和四个设计冻结 schema，并区分 migrator、app、Sample、Fault Lab、Evaluation 和专业 Agent 数据库角色；不得以 H2 替代。

#### Scenario: 锁定容器建立数据库边界
- **GIVEN** 一个空的锁定 PostgreSQL/pgvector Testcontainers 实例
- **WHEN** 执行初始化和 Flyway migration
- **THEN** pgvector、四个 schema 和各角色权限均按冻结边界建立，测试未启动 H2

### Requirement: Spike 最小数据模型
Phase 0 只 MUST（必须）实现 Incident/Run、最小 Agent state/A2A state、Artifact/Evidence/Evaluation 记录，不得提前补齐全部业务表。

#### Scenario: 最小迁移支持 Phase 0
- **GIVEN** 一个空数据库
- **WHEN** 应用 Phase 0 migration 并执行 Spike 与纵切所需写入
- **THEN** 所需记录可持久化且 migration 不包含阶段范围外的完整业务表

### Requirement: 单 Incident 单活动 Run
数据库 MUST（必须）以唯一约束保证同一 Incident 最多只有一个活动 Run，并将并发冲突映射为明确领域结果。

#### Scenario: 并发创建活动 Run
- **GIVEN** 同一 Incident 没有活动 Run
- **WHEN** 两个事务并发创建活动 Run
- **THEN** 仅一个事务成功，另一个得到明确冲突且数据库中只有一个活动 Run

### Requirement: 升级与最小权限
系统 MUST（必须）测试空库迁移和生产前一版本升级；专业 Agent 不得写 Supervisor 领域表，也不得访问 Ground Truth。

#### Scenario: 专业 Agent 越权失败
- **GIVEN** 专业 Agent 数据库角色
- **WHEN** 尝试写 Supervisor 领域表或读取 Ground Truth
- **THEN** PostgreSQL 拒绝操作且审计证据记录被拒绝的角色和对象
