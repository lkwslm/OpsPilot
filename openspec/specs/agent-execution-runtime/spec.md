## Purpose

本规格定义 Phase 6 的 agent-execution-runtime 能力边界、约束、可验证场景，以及阶段验收所需的稳定行为合同。

## Requirements

### Requirement: 唯一 AgentScope 执行边界
系统 MUST 只通过 `AgentExecutionService` 执行六个 Agent，并由 AgentScope Adapter 封装官方 `ReActAgent` loop；AgentScope 类型 MUST NOT 进入 core，业务模块 MUST NOT 实现第二套 Agent 循环。

#### Scenario: 架构检查阻止第二套循环
- **GIVEN** 六角色的运行入口和模块依赖规则
- **WHEN** 执行架构测试并扫描业务层循环入口
- **THEN** 所有角色只依赖 `AgentExecutionService`，core 不引用 AgentScope 类型，新增业务 `while` loop 使测试失败

### Requirement: 有界执行与稳定终止原因
运行时 MUST 通过 Middleware、事件或中断规则执行最大轮次、模型/Tool/Token 预算、deadline、外部取消、重复动作指纹、连续无新 Evidence 和 `NO_PROGRESS` 约束，并以稳定 reasonCode 结束。

#### Scenario: 每种边界独立终止
- **GIVEN** 可分别触发轮次、预算、deadline、取消、重复指纹和无进展的输入
- **WHEN** 逐项执行 Agent
- **THEN** 每次运行以对应 reasonCode 终止并保存剩余预算、未完成工作和最后 checkpoint

### Requirement: 中止后阻断新调用
运行时 MUST 在每次 Model/Tool 调用前检查终止信号；一旦发生取消、预算耗尽、deadline、checkpoint 失败或其他终止事件，MUST NOT 发起新的 Model/Tool 调用，也 MUST NOT 提交迟到响应。

#### Scenario: 取消与迟到响应竞态
- **GIVEN** 一个调用进行中且外部取消先于响应完成
- **WHEN** Provider 随后返回迟到成功响应
- **THEN** 运行时丢弃迟到结果、保持取消终态，且取消事件之后调用计数不再增加

### Requirement: 确定且隔离的会话键
Supervisor MUST 使用 `sessionId="supervisor:" + runId`，专业 Agent MUST 使用 `sessionId=serverAgentId + ":" + a2aTaskId`，MVP `userId` MUST 固定为 `opspilot-system`；continuation MUST 复用原会话，新 attempt MUST 创建新会话且不得继承失败 attempt 上下文。

#### Scenario: continuation 与新 attempt 隔离
- **GIVEN** 一个已有 checkpoint 的 A2A Task 和一个失败后的新 attempt
- **WHEN** 分别恢复 continuation 与启动新 attempt
- **THEN** continuation 恢复原 session，新 attempt 使用由新 Task 计算的 session，二者状态和调用历史不互相覆盖

### Requirement: checkpoint 持久化失败时关闭执行
checkpoint、Usage 或运行状态保存失败 MUST 使执行 fail closed，不得降级到内存或文件状态存储；恢复 MUST 按 `(serverAgentId,userId,sessionId)` 只加载匹配状态并产生可审计恢复事件。

#### Scenario: 保存故障后不继续执行
- **GIVEN** PostgreSQL 状态存储在一轮结束时失败
- **WHEN** 运行时尝试保存 checkpoint
- **THEN** 当前运行以技术失败结束，后续 Model/Tool 调用次数为零，且不存在内存或本地文件回退

### Requirement: 统一事件与受控状态内容
运行时 MUST 把 round、Decision/Result 摘要、动作指纹、Usage、checkpoint、取消和终止映射为项目自有事件；持久状态和审计 MUST NOT 保存隐藏推理、Secret、完整 Prompt 或未经授权的原始正文。

#### Scenario: 事件映射不泄漏隐藏内容
- **GIVEN** AgentScope 事件包含框架内部消息和敏感调用数据
- **WHEN** 事件经过 mapper、脱敏和持久化
- **THEN** 仅保存结构化决策摘要、引用、Usage、关联 ID 和稳定状态，不含隐藏推理或 Secret
