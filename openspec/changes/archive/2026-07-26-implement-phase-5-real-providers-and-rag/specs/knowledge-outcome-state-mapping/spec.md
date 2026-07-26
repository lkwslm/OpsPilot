## ADDED Requirements

### Requirement: 空知识库返回 KB_EMPTY 成功结果
当 collection 没有可用于当前 Run revision 的文档时，Knowledge Search MUST 返回 `COMPLETED + KB_EMPTY`，reference 集合为空、`rerankApplied=false`，并 MUST NOT 调用 Query Embedding、pgvector 候选查询或 Rerank。该结果 MUST 允许 Supervisor 继续现场调查。

#### Scenario: 空 collection 不调用模型链路
- **GIVEN** collection 已存在但当前 Run 快照下没有可搜索文档
- **WHEN** 执行 Knowledge Search
- **THEN** 任务以 `COMPLETED + KB_EMPTY` 结束，三类下游调用计数为零且后续现场 Evidence 收集仍可执行

### Requirement: 非空知识库零候选返回 NO_MATCH 成功结果
当 collection 非空且 Query Embedding、过滤和 pgvector 精确检索全部成功但候选为零时，Knowledge Search MUST 返回 `COMPLETED + NO_MATCH`，reference 集合为空、`rerankApplied=false`，并 MUST NOT 调用 Rerank。系统 MUST 与无历史案例结果保持可区分。

#### Scenario: 合法零候选不伪装故障
- **GIVEN** 非空 active revision、可用 Embedding Provider 与成功数据库查询
- **WHEN** 过滤后的精确召回返回零候选
- **THEN** 结果为 `NO_MATCH` 而非 `KB_EMPTY` 或 `ChainFailure`，Rerank 调用次数为零且审计保存查询 revision 与过滤摘要

### Requirement: 技术失败不得映射为空结果
Embedding、数据库、权限/引用校验以及候选非空后的 Rerank 错误、超时或取消 MUST 映射为具体 `ChainFailure` 和失败 Task/step 结果，不得转换为 `KB_EMPTY`、`NO_MATCH`、MATCH 或任何替代检索结果。

#### Scenario: 数据库超时与 NO_MATCH 分流
- **GIVEN** 非空 knowledge revision 且 Query Embedding 成功
- **WHEN** pgvector 查询超时而没有返回候选集合
- **THEN** 任务返回数据库超时 `ChainFailure` 并按关键链路失败处理，不返回 `NO_MATCH` 且不调用 Rerank

### Requirement: 预算耗尽与无进展进入受限报告
Token/调用/deadline 预算耗尽或 `NO_PROGRESS` MUST 在保存 checkpoint、缺失工作、限制和下一步建议后停止新调用，并映射到 `GENERATING_REPORT`，最终只能产生受限 `PARTIAL` 或 `INCONCLUSIVE`。这些条件 MUST NOT 自动标记为 Provider 技术失败或进入无限重试。

#### Scenario: TOKEN_BUDGET_EXCEEDED 保存后生成报告
- **GIVEN** 一个仍有未完成分析工作的 Run 已耗尽允许预算
- **WHEN** 治理层返回 `TOKEN_BUDGET_EXCEEDED`
- **THEN** Supervisor 不再发起模型/Tool 调用，原子保存 checkpoint 后进入 `GENERATING_REPORT`，报告明确缺失工作与限制

### Requirement: WAITING_INPUT 只用于可回答的业务输入缺失
只有当前步骤返回用户可回答的真实业务输入缺失时，Run 才 MUST 进入 `WAITING_INPUT`；Provider 不可达、技术失败、预算耗尽、无进展、知识空结果或无匹配 MUST NOT 映射为 `WAITING_INPUT`。等待与 continuation MUST 受 Incident deadline 和次数上限约束。

#### Scenario: Provider 不可达不能请求用户输入
- **GIVEN** 当前步骤的 required Provider 暂时不可达且用户无法通过业务输入修复
- **WHEN** Supervisor 映射该失败
- **THEN** Run 保持技术失败或受限报告语义，不进入 `WAITING_INPUT`，错误保留 retryable 与 readiness 信息
