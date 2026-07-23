# domain-identity-error-contracts Specification

## Purpose
定义 Core 的强类型身份、组合身份、稳定根因代码和受控错误链合同，确保跨模块引用、失败传播与外部响应具有一致、稳定且可追踪的领域语义。

## Requirements

### Requirement: 全局强类型身份
Incident、Run、step、A2A Task、Evidence、Artifact 和 Hypothesis MUST 使用强类型身份；除单调递增的 `attempt` 外，对外身份 MUST 为字符串 UUID，API MUST NOT 暴露数据库自增键。

#### Scenario: 非法身份在核心入口被拒绝
- **GIVEN** 非 UUID 字符串、数组序号、可变步骤标题或数据库自增键作为领域身份
- **WHEN** 输入进入 Repository 或状态机之前
- **THEN** 领域校验拒绝输入且不创建领域对象

### Requirement: 组合身份与线路类型一致性
系统 MUST 为 `(runId, stepId, attempt)`、`(remoteAgentId, a2aTaskId)` 等组合身份定义值相等规则；`stepId` 在线路上 MUST 使用 UUID 字符串、Java 领域模型 MUST 使用 `UUID`、PostgreSQL MUST 使用 `uuid`。

#### Scenario: 组合身份值相等
- **GIVEN** 两个字段值相同但实例不同的组合身份
- **WHEN** 在集合、幂等或查找逻辑中比较
- **THEN** 它们被判定为同一身份，任一组成字段不同则不相等

### Requirement: 稳定根因代码
`rootCauseCode` MUST 使用稳定的小写点分代码并通过已知目录校验；未知或无法收敛的根因 MUST 为 `null`，不得生成临时代码。

#### Scenario: 未知根因保持为空
- **GIVEN** 诊断无法匹配已知根因目录
- **WHEN** 构建 RCA 领域结果
- **THEN** `rootCauseCode` 为 `null`，系统不生成占位或临时代码

### Requirement: 受控错误链
`ChainFailure` MUST 携带稳定错误分类、`retryable`、关联 ID、checkpoint 和受控日志引用；敏感 cause MUST 只保留脱敏摘要。

#### Scenario: 敏感错误被脱敏
- **GIVEN** 下游失败 cause 包含 Secret、正文或内部堆栈敏感信息
- **WHEN** 转换为 `ChainFailure`
- **THEN** 结果只包含稳定错误元数据和脱敏摘要，不泄露原始敏感内容

### Requirement: 边界与未知枚举校验
系统 MUST 对 UUID、哈希、`rootCauseCode`、跨 Run 引用和枚举值执行边界校验；未知枚举不得被静默忽略或映射为成功状态。

#### Scenario: 未知枚举 fail closed
- **GIVEN** 机器合同之外的枚举值
- **WHEN** 反序列化并进入领域边界
- **THEN** 输入以稳定校验错误被拒绝且不改变已有状态
