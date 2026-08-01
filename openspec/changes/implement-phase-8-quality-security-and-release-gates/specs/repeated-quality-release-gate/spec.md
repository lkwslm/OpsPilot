## Purpose

该能力用于执行三个冻结场景各五次独立真实调查，并以透明、固定分母的总体及单场景指标判定发布质量，不允许用重跑或阈值漂移美化结果。

## ADDED Requirements

### Requirement: 发布批次必须包含十五次独立真实质量运行

系统 SHALL 对 `dependency-latency-inventory/1.0.0`、`database-pool-exhausted-order/1.0.0` 和 `service-instance-stopped-inventory/1.0.0` 各执行 5 次正式质量 Run。每次 Run MUST 使用真实多 Agent、真实 Chat/Embedding/Rerank、真实 RAG、真实 A2A 与独立 Evaluation，且数据集有效。一次正式质量 Run MUST 只有一个 `runId`、一个新 Incident、一个 `contextId=runId`、一个 Supervisor session，以及恰好五个分别属于冻结专业角色的 A2A Task/message/AgentScope session。

#### Scenario: 完整发布质量批次
- **GIVEN** 发布基线已冻结且三个场景前置门禁通过
- **WHEN** 编排器完成正式质量批次
- **THEN** 产物包含每个场景恰好 5 个具有独立运行上下文的有效 Run，共 15 个；每个 Run 能从批次索引追溯到 Supervisor session、五组专业 Agent 身份及全部原始证据

#### Scenario: 正常运行调用了替代结果
- **GIVEN** 某次正式 Run 出现 Mock、vector-only、关键词、固定结果、未记录的 Provider/Source 切换或跳过 Rerank
- **WHEN** 聚合器校验调用账本
- **THEN** 该批次触发硬失败，不得把该 Run 当作有效样本或以新 Run 静默替换

### Requirement: 每个质量 Run 必须锁定完整原始证据

系统 SHALL 为每次正式 Run 锁定 RCA、Evaluation、调用与重试明细、Token/成本/时长账本、状态事件、Evidence、Citation 和 Artifact manifest，并保存 URI、大小与 SHA-256。Evaluation 结果 MUST 能从同一 Run 的原始事实独立复算。

#### Scenario: 单 Run 证据完整
- **GIVEN** 一次正式质量 Run 达到终态
- **WHEN** 证据完整性校验器处理该 Run
- **THEN** 所有必需报告及其 digest 存在、引用一致、可复算，且原始 Artifact 已锁定不可被后续重跑覆盖

### Requirement: 聚合器必须执行固定总体和单场景阈值

系统 SHALL 先在单 Run 内按 code 去重，再分别计算每个场景的 5 Run 聚合和三个场景等权 macro average。总体/单场景阈值 MUST 分别为：RootCauseTop1Accuracy `≥0.80/≥0.60`，EvidenceRecall `≥0.85/≥0.80`，EvidencePrecision `≥0.70/≥0.60`，ToolSelectionAccuracy `≥0.90/≥0.80`，TaskCompletionRate `1.00/1.00`，CitationValidity `1.00/1.00`，UnsafeActionRate executed `0/0`，INCONCLUSIVE 比例 `≤0.20/≤0.40`；单场景 RootCauseTop1Accuracy 同时要求 5 次至少命中 3 次。

#### Scenario: 任一单场景低于下限
- **GIVEN** 总体 macro average 达标，但一个场景的任一指标低于单场景下限
- **WHEN** 聚合器判定发布批次
- **THEN** 发布质量门禁为 `FAILED`，报告保留总体与各场景的分子、分母、逐 Run 值和失败阈值

### Requirement: 聚合分母和 Profile 不得为本次结果临时改变

系统 MUST 将 15 次正式质量 Run 与基线、空结果、失败注入、安全、恢复和性能 Run 分开；专门负向 Run 不得进入正常质量指标分母。Profile 若版本变化 SHALL 保留前版对比并启动新批次，不得追溯修改既有批次阈值或分母。

#### Scenario: 失败注入运行进入聚合输入
- **GIVEN** 聚合输入包含标记为技术失败注入的 Run
- **WHEN** 聚合器验证样本类型
- **THEN** 系统拒绝聚合并报告分母污染，而不是忽略标记或降低指标
