## ADDED Requirements

### Requirement: 委派前持久化 step attempt 与 messageId
Supervisor MUST 在发出 A2A 请求前原子保存 `(runId,stepId,attempt)`、目标 Agent/skill、唯一 messageId、request hash 和待绑定状态；收到响应后 MUST 通过 CAS/事务保存远端 Task binding 和状态。

#### Scenario: 调用前崩溃可安全恢复
- **GIVEN** Supervisor 已持久化委派意图但在收到 A2A 响应前崩溃
- **WHEN** 进程重启并恢复该 attempt
- **THEN** 使用原 messageId 重放或查询获得同一 Task，不创建重复 attempt 或专业副作用

### Requirement: 重试创建全新 attempt 与远端 Task
失败、拒绝或重新规划后的重试 MUST 创建新 attempt、新 messageId、新 A2A Task 和新专业 Agent session；旧 attempt/Task 终态 MUST NOT 复活、覆盖或把失败上下文继承给新 attempt。

#### Scenario: 失败后重试身份隔离
- **GIVEN** attempt 1 的远端 Task 已 `FAILED`
- **WHEN** Supervisor 允许重试
- **THEN** 创建 attempt 2、不同 messageId/Task/session，attempt 1 保持可审计终态且迟到响应不能修改 attempt 2

### Requirement: stream 断连与重启按 binding 对账
Client stream 断开、Supervisor/专业服务重启或订阅游标丢失时，reconciler MUST 从本地 binding 出发调用远端 Get/Subscribe，对比持久 Task、Artifact 和本地 CAS 版本后恢复；MUST NOT 把 attempt 直接重置为初始状态。

#### Scenario: stream 断开后恢复 Artifact
- **GIVEN** 专业 Task 已持久化完成 Artifact，但 Supervisor stream 在完成事件前断开
- **WHEN** Supervisor 重启并执行 Get/Subscribe 对账
- **THEN** 原 attempt 接收同一 Artifact 一次且仅一次，不重跑已完成专业工作

### Requirement: 取消传播与竞争确定收敛
Run/step 取消 MUST 持久化取消意图并传播到绑定的远端 Task；远端完成与取消并发时 MUST 依据已提交顺序、终态单调和 CAS 确定唯一结果，不得重复接收副作用。

#### Scenario: 完成与取消竞争
- **GIVEN** cancel 请求和完整 Artifact 完成响应并发到达
- **WHEN** Task Store 与 Supervisor 分别提交
- **THEN** 只有先成功提交的合法终态生效，另一方对账到该终态且不覆盖或重复写入

### Requirement: A2A、step 与 Run 状态严格映射
系统 MUST 按冻结矩阵映射全部 A2A TaskState 与本地 step attempt；`AUTH_REQUIRED` MUST 只映射 `WAITING_AUTH`，不得映射业务 `WAITING_APPROVAL`。顶层 Supervisor Task MUST 与 Run 的 `COMPLETED|FAILED|CANCELLED` 分别收敛到 A2A `COMPLETED|FAILED|CANCELED`。

#### Scenario: 认证挑战不触发业务审批
- **GIVEN** 专业 Task 返回 `AUTH_REQUIRED`
- **WHEN** Supervisor 映射本地状态
- **THEN** attempt 进入 `WAITING_AUTH` 并等待凭证处理，Run 不进入 `WAITING_APPROVAL` 且不创建审批记录

### Requirement: 专业 Agent 不写 Supervisor 权威状态
专业服务数据库身份 MUST NOT 写 Incident、Run、Supervisor step、Evidence、Hypothesis 或 RCA 权威表；专业服务只能写自身 A2A Task/AgentScope 状态和 Artifact 交付记录，Supervisor 负责校验与领域接收。

#### Scenario: 专业数据库角色越权写入
- **GIVEN** 专业服务的数据库凭证
- **WHEN** 尝试直接更新 Supervisor Run 或 Evidence 表
- **THEN** 数据库授权拒绝写入，原领域状态不变并产生受控安全审计
