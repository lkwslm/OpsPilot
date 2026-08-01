## Purpose

该能力用于从每次调查的原始事件独立复核日志、Trace、指标、关联标识与费用账本，并以可解释的多个效率维度阻止失控调查进入发布候选。

## ADDED Requirements

### Requirement: 全链路遥测必须结构化、可关联且低基数

系统 SHALL 验证 Incident、Run、step、A2A、Tool、Provider、Evidence、RCA 与 Evaluation 的结构化日志和 Trace 能通过 `requestId/traceId/runId/stepId/a2aTaskId/invocationId` 关联；错误必须携带相同关联链、上游状态、checkpoint 与 `logArtifactId`。发布指标 SHALL 使用有界标签集合并覆盖 Dashboard 与告警所需计数、延迟、错误、重试、等待、outbox、租约和 Provider/Source 健康数据。

#### Scenario: 从产品错误追溯完整调用链
- **GIVEN** 某 Tool 或 A2A 调用产生技术错误
- **WHEN** 验证器从 REST/SSE 错误中的关联 ID 查询日志、Trace、状态与错误 Artifact
- **THEN** 能无歧义追溯所有 attempt、上游响应、重试决策和最终状态，且所有字段已脱敏

#### Scenario: 指标使用运行身份作为标签
- **GIVEN** metrics 暴露 `runId`、`incidentId`、Prompt、URL 或错误原文等高基数/敏感标签
- **WHEN** 指标基数与标签策略门禁运行
- **THEN** 可观测性门禁为 `FAILED`，报告定位指标名和违规标签但不复制敏感值

### Requirement: Token 与成本账本必须可独立复算

系统 SHALL 对每个 Run 记录实际 input/output Token、估算方法、单价/版本、estimated cost、Provider/Tool/A2A 调用、失败、重试、等待与 wall-clock，并从原始 usage 和事件独立复算。差异 MUST 为零或有版本化、可审计解释；失败调用消耗与重试等待不得遗漏。

#### Scenario: 账本遗漏失败调用 Token
- **GIVEN** Provider attempt 消耗 Token 后以技术失败结束并触发重试
- **WHEN** reconciler 比较原始 usage 与 Run ledger
- **THEN** 失败 attempt 的 Token 和费用进入总账，任何未解释差异使门禁 `FAILED`

### Requirement: InvestigationEfficiency 必须逐维执行固定上限

系统 SHALL 对每个正式质量 Run 分别比较 Supervisor rounds `≤12`、每个专业 Agent rounds `≤8`、总 Tool calls `≤30`、专业 Agent A2A attempts `≤10`、不含用户等待的 wall-clock `≤600s`，以及 Token 不超过冻结 Evaluation Profile budget。系统 MUST 保存每维实际值、上限和判定，不得合成为单一效率分数，也不得用平均值掩盖单 Run 超限。

#### Scenario: 单个专业 Agent 超过 rounds 上限
- **GIVEN** 其余效率维度和总体均值均达标，但一个专业 Agent 在一个 Run 中执行 9 rounds
- **WHEN** 效率门禁逐 Run 评估
- **THEN** 该 Run 和阶段效率门禁为 `FAILED`，报告单独列出违规 Agent、实际值与上限

#### Scenario: Token budget 未冻结
- **GIVEN** Evaluation Profile 没有可解析的具体 Token 上限
- **WHEN** 效率评估器处理正式质量批次
- **THEN** 门禁为 `BLOCKED`，不得忽略 Token 维度或以本批次观测值回填阈值
