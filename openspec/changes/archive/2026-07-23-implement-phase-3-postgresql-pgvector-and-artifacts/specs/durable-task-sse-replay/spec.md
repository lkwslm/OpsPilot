## ADDED Requirements

### Requirement: SKIP LOCKED 原子任务领取
worker MUST 使用 `FOR UPDATE SKIP LOCKED` 按状态、`available_at`、priority 领取任务，并在同一事务中设置 lease owner 与 lease until；并发 worker MUST NOT 同时获得同一任务。

#### Scenario: 并发 worker 领取任务
- **GIVEN** 一个已到可执行时间且未被租用的持久任务
- **WHEN** 两个 worker 并发领取
- **THEN** 只有一个 worker 获得并原子写入租约，另一个跳过该任务且不执行副作用

### Requirement: 租约恢复必须保留幂等与尝试语义
恢复逻辑 MUST 检查最大 attempts、过期租约、idempotency key 和已提交副作用；过期任务 MUST 经过显式状态判定，MUST NOT 直接重置为初始状态，恢复后不得重复已提交副作用。

#### Scenario: 副作用提交后进程中止
- **GIVEN** worker 已提交外部可识别副作用但在确认任务完成前中止
- **WHEN** 租约到期并由另一个 worker 恢复
- **THEN** 恢复逻辑通过幂等键与已提交副作用记录避免重复执行，并按 attempts 与最新状态继续或终止

### Requirement: SSE 事件按 Run 持久化与重放
SSE event MUST 绑定 `runId` 并只在事务提交后可见；Repository MUST 按 `(runId,eventId)` 重放 `Last-Event-ID` 之后的已提交事件，顺序稳定且不包含其他 Run 的事件。

#### Scenario: 同 Run 断线续传
- **GIVEN** 一个 Run 已提交有序 SSE 事件且客户端保存中间 `Last-Event-ID`
- **WHEN** 客户端以相同 `runId` 重连
- **THEN** 只按稳定顺序返回该 ID 之后的已提交事件，不返回未提交或已确认事件

#### Scenario: 跨 Run Last-Event-ID
- **GIVEN** `Last-Event-ID` 属于另一个 Incident/Run
- **WHEN** 客户端以目标 Run 请求重放
- **THEN** Repository 返回零结果或稳定拒绝，不泄漏来源 Run 的事件或存在性细节

### Requirement: projector 失败隔离
SSE projector 失败 MUST 只使对应 outbox event 保持可重试并产生告警，MUST NOT 回滚、重写或伪造已经提交的核心状态与 SSE 事实。

#### Scenario: 提交后 SSE 投影失败
- **GIVEN** 核心事务和 outbox event 已提交
- **WHEN** SSE projector 被注入故障
- **THEN** 核心状态保持不变，outbox 保持可重试且产生告警，恢复后不重复事件
