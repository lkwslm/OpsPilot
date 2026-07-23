# transactional-checkpoint-outbox Specification

## Purpose

定义 checkpoint、乐观锁、调用审计、绑定关系和 outbox event 的事务一致性，以及提交后投影的幂等与失败边界。

## Requirements

### Requirement: 原子 checkpoint 内容
一次 PostgreSQL checkpoint 或结果接收 MUST 在同一真实 UnitOfWork 内提交 Run/step/状态与乐观锁版本、A2A binding、Tool/Model/A2A 调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox event，不得部分可见；JPA 与 JDBC/jOOQ 写入 MUST 共享同一 DataSource 与 transaction manager。

#### Scenario: checkpoint 中途失败
- **GIVEN** 保存状态后、写入绑定或 outbox 前注入数据库故障
- **WHEN** PostgreSQL UnitOfWork 尝试提交 checkpoint
- **THEN** 整个事务回滚，Run/step、状态、版本、审计、绑定、分析关系和事件均不可见

### Requirement: CAS 冲突重新判定
CAS 冲突后应用服务 MUST 重读最新状态并重新判断迁移，不得覆盖写或沿用基于旧版本的决策。

#### Scenario: 并发取消与完成竞争
- **GIVEN** 两个执行者基于同一版本分别提交取消和完成
- **WHEN** 后提交者遇到 CAS 冲突
- **THEN** 后提交者重读并按最新终态重新判定，不能覆盖已提交结果

### Requirement: Domain Event 提交后投影
系统 MUST 区分 Domain Event 事实与命令；事件只有在事务提交后才能投影，消费者 MUST 使用 `eventId` 幂等。

#### Scenario: 重复投递同一事件
- **GIVEN** 已成功投影的 `eventId` 再次投递
- **WHEN** projector 消费重复事件
- **THEN** 不产生重复投影或副作用，并保留幂等消费结果

### Requirement: 仅测试内存 UnitOfWork
系统 MUST 提供支持提交、回滚、故障点和重复消费的内存 UnitOfWork，但该实现只能位于测试源码或 test fixture，MUST NOT 注册为部署 Provider 或生产回退。

#### Scenario: 生产装配扫描测试实现
- **GIVEN** Server 的生产 composition root 和 Profile
- **WHEN** 架构测试扫描可部署 Bean/Provider
- **THEN** 不存在内存 UnitOfWork 或测试 Repository 注册

### Requirement: 已提交核心状态不受投影失败回滚
事务回滚 MUST 不留下状态、审计、绑定或事件；事务已提交后 projector 失败 MUST 记录可重试投影失败，但不得回滚或改写核心状态。

#### Scenario: 提交后 projector 失败
- **GIVEN** checkpoint 已提交且 projector 在处理 outbox event 时失败
- **WHEN** 查询核心状态和事件
- **THEN** 核心状态保持已提交，事件保持可重试，失败不会伪装成事务回滚

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
