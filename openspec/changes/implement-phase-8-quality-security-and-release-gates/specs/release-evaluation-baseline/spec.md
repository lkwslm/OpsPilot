## Purpose

该能力用于在正式发布质量运行前冻结全部可影响结果的配置、模型、知识和机器身份，并以不可变账本保证每次运行可追溯、不可静默替换。

## ADDED Requirements

### Requirement: 正式运行前必须冻结发布评测快照

系统 SHALL 在任何正式质量 Run 开始前冻结 commit、temperature=0、六角色 Prompt 与模型 revision、知识 collection revision、三个 Scenario version、Evaluation Profile version、开发机硬件与驱动身份，并保存规范化摘要。若 Profile 尚无具体 Token evaluation budget，系统 MUST 先执行不计入发布聚合的基线 Run、发布新 Profile version 并冻结预算；在此之前正式运行状态 SHALL 为 `BLOCKED`。

#### Scenario: 冻结完整基线后允许正式运行
- **GIVEN** 所有前置能力、Secret、Runner 和真实模型可用，且 Evaluation Profile 已包含具体 Token budget
- **WHEN** 发布运行编排器校验冻结快照
- **THEN** 系统记录全部身份及 SHA-256，并允许启动第一条正式质量 Run

#### Scenario: Token budget 尚未冻结
- **GIVEN** 当前 Evaluation Profile 未包含具体 Token evaluation budget
- **WHEN** 操作者请求启动 15 次正式质量 Run
- **THEN** 系统只允许执行标记为基线且不进入聚合分母的 Run，并将正式门禁标记为 `BLOCKED`，直到新 Profile version 发布

### Requirement: 正式运行期间必须拒绝基线漂移

系统 SHALL 在每次 Run 开始与结束时重新计算冻结项摘要，并 MUST 在 commit、Prompt、模型、知识 revision、Scenario、Profile、temperature 或硬件身份任一漂移时终止发布批次；已完成 Run 不得与漂移后的 Run 合并聚合。

#### Scenario: 批次中途发生配置漂移
- **GIVEN** 发布批次已有至少一个有效 Run
- **WHEN** 后续 Run 的冻结摘要与批次基线不一致
- **THEN** 系统将批次标记为 `FAILED`，保留差异证据，且不通过重跑覆盖原 Run

### Requirement: Run ledger 必须完整且不可静默覆盖

系统 SHALL 以一条 ledger 项记录一个完整基线或正式 Run，并保存 `incidentId`、`runId`、`datasetRunId`、`a2aContextId`、`supervisorSessionId`、attempt、开始/结束时间、环境 digest、结果、Artifact URI 与 SHA-256。每个正式质量 Run 的 `a2aContextId` MUST 等于 `runId`，并包含恰好五个按专业角色记录的 `a2aTasks[]` 元素；每个元素 MUST 保存 `agentId`、`a2aTaskId`、`messageId` 与 `agentScopeSessionId`。Supervisor session 属于该 Run，但 MUST NOT 伪装成第六个 A2A Task。不同正式 Run 的 context、Supervisor session、Task、message 和专业 Agent session MUST 唯一，不得复用模型上下文。缺少环境、凭证或真实 Provider 时 SHALL 显式记录 `BLOCKED`，不得显示为 `PASSED` 或跳过。

#### Scenario: 复用数据集但隔离运行上下文
- **GIVEN** 同一有效 `datasetRunId` 被授权用于同场景的多个质量 Run
- **WHEN** 编排器创建下一次正式 Run
- **THEN** ledger 记录相同 `datasetRunId`，以及全新的 `incidentId`、`runId`、`a2aContextId`、Supervisor session 和五组专业 Agent Task/message/session，并保留各自 Artifact digest

#### Scenario: 一个正式 Run 的专业身份不完整
- **GIVEN** ledger 项缺少 Supervisor session，或五个专业角色中任一角色的 Task、message、AgentScope session 缺失或重复
- **WHEN** ledger 校验器处理该正式质量 Run
- **THEN** 系统拒绝写入该项，不得选择一个代表性 Task/session 代替完整身份集合

#### Scenario: 环境恢复失败导致运行无效
- **GIVEN** 某次 Run 的数据集或环境恢复校验失败
- **WHEN** 编排器结束该 Run
- **THEN** ledger 保留原身份与失败原因，不以静默重跑或替换记录维持预期样本数
