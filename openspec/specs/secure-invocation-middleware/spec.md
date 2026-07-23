# secure-invocation-middleware Specification

## Purpose

规定所有受控 Port 调用的安全中间件顺序、短路语义、权限与风险策略、脱敏审计及失败隔离行为，确保拒绝路径不会绕过控制点或泄露敏感上下文。

## Requirements

### Requirement: 固定安全调用顺序
所有受控 Port 调用 MUST 严格执行 `Validate Schema → Authorize → Require Approval → Enforce Budget/Deadline → Execute Port → Normalize/Redact → Audit`，安全步骤不得移除、绕过或重排。

#### Scenario: 中间件顺序验证
- **GIVEN** 记录每个步骤进入与退出的测试调用
- **WHEN** 执行一次成功的受控 Port 调用
- **THEN** 事件严格按冻结顺序出现，故意重排任一步骤会使顺序测试失败

### Requirement: 前置短路与结果校验
Schema、授权、审批、预算或 deadline 失败 MUST NOT 执行 Port；Normalize、Redact 或结果校验失败 MUST NOT 把调用标记为成功。

#### Scenario: 审批缺失时短路
- **GIVEN** 一个需要审批但没有有效批准的动作
- **WHEN** 调用进入 `Require Approval`
- **THEN** 调用以稳定拒绝结果结束，Port 执行次数为零且 Audit 记录拒绝

### Requirement: 权限交集与风险策略
有效权限 MUST 是主体、Agent、Tool 和运行上下文权限的交集；策略 MUST 区分 `READ_ONLY/CONTROLLED_EXECUTION/HIGH_RISK`，MVP 对 `HIGH_RISK` MUST 无条件拒绝。

#### Scenario: HIGH_RISK 即使已审批也拒绝
- **GIVEN** 一个具有完整身份和审批的 `HIGH_RISK` 动作
- **WHEN** 权限与风险策略评估
- **THEN** 动作仍被拒绝且不执行 Port

### Requirement: 全路径脱敏审计
Audit MUST 在成功、拒绝、失败和取消路径记录主体、动作指纹、有效权限、审批、预算、摘要和错误，但 MUST NOT 记录 Secret 或原始正文。

#### Scenario: 失败路径审计不泄密
- **GIVEN** Port 失败并返回含 Secret 的原始错误
- **WHEN** 调用经过 Normalize/Redact 和 Audit
- **THEN** 审计包含关联和稳定错误信息但不含 Secret、Prompt 或原始响应正文

### Requirement: 可观测性失败隔离与安全失败关闭
tracing/metrics observer 失败 MUST 被隔离并记录自身错误；任何安全中间件异常 MUST fail closed，且不得继续执行后续受保护动作。

#### Scenario: observer 与授权器分别异常
- **GIVEN** tracing observer 或 Authorize 步骤被注入异常
- **WHEN** 分别执行调用
- **THEN** observer 异常不改变已确定的业务结果，而授权异常使调用拒绝且 Port 执行次数为零
