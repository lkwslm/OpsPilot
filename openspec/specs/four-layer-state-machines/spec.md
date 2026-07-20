# four-layer-state-machines Specification

## Purpose
定义 Agent Endpoint、A2A Task、Step Attempt 与 Incident Run 四层状态及权威迁移关系。

## Requirements

### Requirement: 四层状态类型独立
Agent endpoint、A2A Task、本地 step attempt 和 Incident Run MUST 使用不同的状态类型和所有者，不得复用跨层通用 `status`。

#### Scenario: 跨层状态赋值被拒绝
- **GIVEN** 一个 A2A Task 状态值
- **WHEN** 代码尝试把它直接赋给 step attempt 或 Incident Run
- **THEN** 类型或边界校验拒绝该赋值，必须经过显式映射策略

### Requirement: 权威迁移表与终态
每层状态机 MUST 把权威迁移矩阵编码为显式 allowlist；同状态进度事件不得计为迁移，终态 MUST NOT 有后继。

#### Scenario: 非法迁移保持版本不变
- **GIVEN** 任一未列入 allowlist 的状态边或从终态离开的请求
- **WHEN** 状态机执行迁移
- **THEN** 返回 `INVALID_STATE_TRANSITION` 且快照版本不变

### Requirement: A2A 到本地状态映射
A2A `INPUT_REQUIRED`、`AUTH_REQUIRED`、`COMPLETED` MUST 分别映射为本地 `WAITING_INPUT`、`WAITING_AUTH`、`VALIDATING_RESULT`；远端完成不得直接完成 step，只有 Artifact 校验通过后 step 才可完成。

#### Scenario: 已完成 Task 的 Artifact 未通过校验
- **GIVEN** A2A Task 到达 `COMPLETED` 但 Artifact 媒体类型、Schema、哈希、身份或权限校验失败
- **WHEN** 本地状态映射处理该结果
- **THEN** step 不进入完成态并记录稳定校验失败

### Requirement: attempt 重试与协调语义
step attempt MUST 支持 `DISPATCHING`、`RECONCILING`、`RETRY_SCHEDULED` 和 `CANCEL_REQUESTED` 语义；重试 MUST 创建新的单调递增 attempt，旧终态不得被覆盖。

#### Scenario: 终态 attempt 触发重试
- **GIVEN** 一个已终止且允许重试的 attempt
- **WHEN** 调度策略安排重试
- **THEN** 系统创建新 attempt 并保留旧 attempt 的终态和审计事实

### Requirement: Agent endpoint 健康状态独立
Endpoint MUST 覆盖 `UNKNOWN/PROBING/READY/UNAVAILABLE/DRAINING/DISABLED`，并记录设计规定的 `reasonCode`、`retryable`、Card digest 和探针时间；端点变化不得篡改既有 Task，单个 Task 失败不得直接判定端点不可用。

#### Scenario: 单 Task 失败不污染端点
- **GIVEN** READY endpoint 上的一个 A2A Task 失败
- **WHEN** 处理 Task 终态
- **THEN** endpoint 状态保持由独立探针策略决定，既有 Task 历史不被改写

### Requirement: Incident 等待取消与报告状态分离
Incident Run MUST 严格区分 `WAITING_INPUT`、`WAITING_APPROVAL`、`CANCELLING` 和 `GENERATING_REPORT`，并通过显式规则映射顶层 Task/Run 终态。

#### Scenario: 等待审批不能伪装成等待输入
- **GIVEN** Run 需要受控执行审批
- **WHEN** 调度进入暂停状态
- **THEN** Run 进入 `WAITING_APPROVAL` 而非 `WAITING_INPUT`，恢复条件只接受匹配审批结果
