## Purpose

该能力用于系统性验证 Agent、Task、step 与 Run 在并发竞争、进程中断和依赖恢复期间保持合法状态、幂等副作用与可审计终态。

## ADDED Requirements

### Requirement: 状态与并发矩阵必须覆盖四层生命周期不变量

系统 SHALL 覆盖 Agent、A2A Task、step 和 Run 的全部合法状态与拒绝转换，并验证 CAS、同一 Incident 单活动 Run、attempt 单调增长、租约所有权、取消竞争、Artifact 校验失败和 outbox 重放。任何并发路径 MUST 最终只有一个权威终态且不得产生重复副作用。

#### Scenario: 两个请求并发启动同一 Incident
- **GIVEN** 同一 Incident 没有活动 Run
- **WHEN** 两个启动请求竞争创建 Run
- **THEN** CAS 只允许一个 Run 成为活动实例，另一请求得到可定位冲突，且不存在双重 Agent/A2A 副作用

#### Scenario: 取消与完成竞争
- **GIVEN** Run 的最后一步完成与取消请求并发发生
- **WHEN** 两方使用各自读取的版本提交状态
- **THEN** 只有一个 CAS 成功，失败方重读权威状态，事件、checkpoint 与 Artifact 不产生矛盾终态

#### Scenario: Artifact 校验失败
- **GIVEN** 上游 Task 返回的 Artifact 不存在、哈希不符或越权
- **WHEN** Run 尝试推进依赖该 Artifact 的步骤
- **THEN** 状态机拒绝推进并记录 `ChainFailure`、attempt、上游状态、关联 ID 与 `logArtifactId`

### Requirement: 重启后必须对账并在六十秒内继续或明确失败

系统 SHALL 分别中断专业 Agent 进程、客户端 stream、PostgreSQL、Artifact 服务、模型服务、Prometheus、Jaeger 和 Toxiproxy，并验证 checkpoint、租约接管、远端 Task Get/Subscribe 对账、幂等 messageId、取消和恢复路径。重启后 MUST 在 60 秒内继续或进入明确 `FAILED`，不得盲重发不确定 A2A Task。

#### Scenario: A2A stream 断开且远端 Task 已完成
- **GIVEN** 本地在响应前失去 stream，但远端 Task 已达到完成终态
- **WHEN** 恢复器读取 checkpoint 并 Get/Subscribe 原 Task
- **THEN** 系统消费原结果并继续，不创建重复 Task 或重复 Tool 副作用

#### Scenario: 租约持有进程重启
- **GIVEN** 活动 Run 的租约持有进程在已提交 checkpoint 后退出
- **WHEN** 新实例在租约过期后接管并对账
- **THEN** Run 在 60 秒内从安全 checkpoint 继续或明确失败，attempt 和接管事件可审计

#### Scenario: 依赖恢复后仍不一致
- **GIVEN** 数据库、Artifact、模型或可观测组件恢复，但 checkpoint 与上游状态无法安全对齐
- **WHEN** 恢复期限届满
- **THEN** 系统进入 `FAILED` 并保存不一致证据，不以跳过步骤或重建状态伪装恢复

### Requirement: 可选 projector 失败不得回滚诊断状态

SSE 与 metrics 等可选 projector 失败时，系统 SHALL 只对各自 outbox 事件执行有界重试、记录 projector 错误并在超过错误预算时告警；已提交 Incident、Run、Task、step 或 RCA 状态 MUST NOT 回滚。重放 MUST 幂等且保持事件顺序语义。

#### Scenario: SSE projector 持续不可用
- **GIVEN** 诊断事务与 outbox 事件已提交
- **WHEN** SSE projector 连续处理失败并耗尽重试
- **THEN** 权威诊断状态保持已提交，projector 记录失败并告警，恢复后重放不产生重复事件
