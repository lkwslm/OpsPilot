## ADDED Requirements

### Requirement: Context Builder 只装配最小受权上下文
系统 MUST 按版本化公共 Prompt、角色 Prompt、结构化任务、必要状态摘要、允许 Tool Schema、Evidence 引用、受权 Artifact 最小片段和输出 Schema 构建上下文，不得广播完整会话、完整仓库、其他 Agent 完整输出、隐藏推理或未授权原始 Artifact。

#### Scenario: Artifact 按引用最小展开
- **GIVEN** 当前任务只需核验一条 Evidence 所引用 Artifact 的有限位置且调用方具备权限
- **WHEN** Context Builder 构建模型输入
- **THEN** 上下文只包含该位置的受控片段、稳定引用与 Prompt 版本，不包含整个 Artifact 或无关会话内容

### Requirement: 上下文压缩保留关键语义
上下文接近模型上限时，`ContextCompactor` MUST 依次压缩重复工具输出、已完成步骤细节和低相关候选，并 MUST 保留决策、结论、未解决问题、关键约束及 Artifact 引用；系统 MUST NOT 通过简单截断最早消息满足上限。

#### Scenario: 压缩顺序与保留集合
- **GIVEN** 同一上下文同时包含重复工具输出、已完成步骤细节、低相关候选和早期关键决策
- **WHEN** 需要释放固定 Token 空间
- **THEN** 压缩结果遵循冻结优先级、保留全部关键决策与引用，并可由测试证明不是首部截断

### Requirement: Token 调用数与成本账本强制对账
`TokenBudgetManager` MUST 在调用前按单次输入/输出、Agent/任务累计、Incident 总 Token、调用次数及可选金额预算预留，在调用后优先使用 Provider Usage 对账。Usage 缺失时 MUST 使用版本化保守估算并标记，失败调用和重试 MUST 计入预算，任何缺失 Usage 不得记为零。

#### Scenario: 失败且无 Usage 的调用仍入账
- **GIVEN** 一次 Provider 调用发送成功后超时且没有返回 Usage
- **WHEN** 调用治理完成失败处理
- **THEN** 账本以保守估算记录输入/输出或已知下界、调用次数、成本估算标志和失败 attempt，剩余预算相应减少

### Requirement: deadline 层级约束所有等待与重试
系统 MUST 落实 `Incident deadline > Agent step deadline > Provider/Tool request deadline > connect + response/read timeout`，子 deadline 必须从父层剩余时间派生。semaphore 等待、连接、读取、`Retry-After`、退避和 jitter MUST 计入父 deadline，任何子层不得越过父层。

#### Scenario: 退避将越过父 deadline
- **GIVEN** Provider 返回可重试 503，但计算出的下一次退避加请求超时超过父 deadline 剩余时间
- **WHEN** 治理中间件决定是否重试
- **THEN** 系统不开始下一 attempt，取消当前链路并返回 deadline `ChainFailure`

### Requirement: 重试分类有界且禁止自动 failover
LLM、Embedding 和 Rerank 的默认总尝试次数 MUST 不超过 2。401/403/模型不存在 MUST 不重试；400、Schema 与上下文超限 MUST 不原样重放；429 MUST 尊重受本地上限和父 deadline 约束的 `Retry-After`；网络及 502/503/504 仅可在总 deadline 内指数退避并加入 jitter。系统 MUST NOT 自动切换 Provider。

#### Scenario: 429 受本地等待上限约束
- **GIVEN** Provider 返回的 `Retry-After` 大于本地上限但父 deadline 尚有余量
- **WHEN** 治理中间件计算第二次 attempt
- **THEN** 实际等待不超过本地上限，总 attempt 不超过 2，审计记录原值、裁剪值和决定，实际 Provider ID 保持不变

### Requirement: 每个 Provider Profile 使用独立 bulkhead
每个 Provider Profile MUST 使用独立 semaphore/bulkhead；获取等待、执行、失败和重试 MUST 归属相同配置快照并进入预算与审计。超时或取消 MUST 中止请求并在所有路径释放 permit。

#### Scenario: 取消释放 permit 且隔离其他 Profile
- **GIVEN** Profile A 已占满并发 permit，Profile B 仍有独立容量
- **WHEN** A 的一个请求被取消
- **THEN** A 的 permit 被释放且后续请求可获得，B 的调用不受阻塞，审计记录等待和取消耗时

### Requirement: 预算不足按冻结顺序降载并保存恢复状态
调用前预算不足时，系统 MUST 依次尝试压缩上下文、降低知识候选数、缩小代码/时间窗、拆分子任务和 Supervisor 重规划；仍不足时 MUST 返回 `TOKEN_BUDGET_EXCEEDED`，保存 checkpoint、缺失工作与剩余预算，并停止新增模型调用。

#### Scenario: 全部降载仍不足
- **GIVEN** 当前任务经过所有允许的降载步骤后仍超过剩余预算
- **WHEN** 调用治理准备发送模型请求
- **THEN** 请求不发送，checkpoint 记录已尝试策略、缺失工作和可恢复状态，后续状态映射进入受限报告路径
