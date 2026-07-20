# transactional-checkpoint-outbox Specification

## Purpose

定义 checkpoint、乐观锁、调用审计、绑定关系和 outbox event 的事务一致性，以及提交后投影的幂等与失败边界。

## Requirements

### Requirement: 原子 checkpoint 内容
一次 checkpoint MUST 在同一 UnitOfWork 内提交状态与乐观锁版本、Tool/Model/A2A 调用审计、Evidence/Hypothesis/Artifact 绑定和 outbox event，不得部分可见。

#### Scenario: checkpoint 中途失败
- **GIVEN** 保存状态后、写入绑定或 outbox 前注入故障
- **WHEN** UnitOfWork 尝试提交 checkpoint
- **THEN** 整个事务回滚，状态、版本、审计、绑定和事件均不可见

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
