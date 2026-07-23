## MODIFIED Requirements

### Requirement: 原子 checkpoint 内容
一次 PostgreSQL checkpoint 或结果接收 MUST 在同一真实 UnitOfWork 内提交 Run/step/状态与乐观锁版本、A2A binding、Tool/Model/A2A 调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox event，不得部分可见；JPA 与 JDBC/jOOQ 写入 MUST 共享同一 DataSource 与 transaction manager。

#### Scenario: checkpoint 中途失败
- **GIVEN** 保存状态后、写入绑定或 outbox 前注入数据库故障
- **WHEN** PostgreSQL UnitOfWork 尝试提交 checkpoint
- **THEN** 整个事务回滚，Run/step、状态、版本、审计、绑定、分析关系和事件均不可见

## ADDED Requirements

### Requirement: 分析封账后的写保护
当 Run 的 `analysis_sealed_at` 非空时，系统 MUST 拒绝新增或修改该 Run 的 Evidence、Hypothesis、Hypothesis-Evidence 与 Hypothesis-Verification。报告生成失败后的重试 MUST 只读取同一封账数据，新 Evidence MUST 进入新 Run。

#### Scenario: 封账后追加分析事实
- **GIVEN** 一个已经设置 `analysis_sealed_at` 的 Run
- **WHEN** 任一 Repository 路径尝试新增 Evidence 或改变 Hypothesis 关系
- **THEN** 数据库或 UnitOfWork 以稳定错误拒绝整笔事务，原封账数据保持不变

### Requirement: PostgreSQL outbox 发布与投影幂等
outbox event MUST 与核心事实同事务写入且提交前不可见；publisher/projector MUST 使用 `eventId` 幂等，重复消费不得生成重复投影，提交后的投影失败只能重试和告警。

#### Scenario: 提交可见性与重复投影
- **GIVEN** 一个事务内已写但尚未提交的 outbox event
- **WHEN** projector 在提交前查询并在提交后重复消费同一 `eventId`
- **THEN** 提交前查询不可见，提交后首次投影成功，后续消费不重复投影或副作用
