## Purpose

该能力用于通过可枚举的组件、故障类型和能力关键性矩阵，证明技术故障按有限重试、明确终止或受限缺失的合同处理，且不会触发隐藏降级。

## ADDED Requirements

### Requirement: 技术失败目录必须覆盖全部真实调用边界

系统 SHALL 对 LLM、Embedding、Rerank、KnowledgeAgent、五个专业 A2A endpoint，以及每个已登记 Tool 和 Source 分别执行不可用、超时、鉴权失败与 Schema 错误用例。每个 case MUST 记录组件/endpoint、`sourceId/sourceKind/adapterId`、注入故障、attempt、deadline、上游状态、checkpoint、关联 ID、`logArtifactId` 与期望关键性。

#### Scenario: 生成完整失败矩阵
- **GIVEN** 当前冻结 Profile、Agent Card、Tool Registry 和 Source Registry 已加载
- **WHEN** 失败目录生成器展开测试 case
- **THEN** 每个已登记真实调用边界都有四类故障 case，且目录差异校验能发现遗漏或未登记新增项

### Requirement: 关键性必须决定技术失败结果

系统 SHALL 按场景合同执行 `MANDATORY`、`MANDATORY_WHEN_CANDIDATES_EXIST`、`CONDITIONAL` 和 `OPTIONAL_APPROVED` 语义。关键能力在有限重试耗尽后 MUST 使 step 与 Incident `FAILED`；仅明确允许继续的缺失 SHALL 产生带 `ChainFailure` 的结构化 `missingEvidence` 和受限报告。

#### Scenario: MANDATORY 能力耗尽重试
- **GIVEN** LLM、Artifact、PostgreSQL、关键 A2A endpoint、LogQueryTool 或 HealthQueryTool 发生持续技术故障
- **WHEN** 有界重试在父级 deadline 内耗尽
- **THEN** step 与 Incident 进入 `FAILED`，完整错误链可定位，且不得生成伪成功 RCA

#### Scenario: MANDATORY_WHEN_CANDIDATES_EXIST 的 Rerank 故障
- **GIVEN** 知识检索已产生候选且 Rerank 不可用、超时、鉴权失败或 Schema 无效
- **WHEN** 有界重试耗尽
- **THEN** Incident 进入 `FAILED`，不得以 vector-only、关键词或固定排序继续

#### Scenario: CONDITIONAL Source 被允许缺失
- **GIVEN** 场景未要求该类 Evidence、至少一种独立观测源成功且当前假设满足继续条件
- **WHEN** 对应 Source 技术失败
- **THEN** 系统记录 `ChainFailure + missingEvidence` 并生成明确限制的报告，不把故障伪装为空结果

#### Scenario: 已批准 Sandbox 技术失败
- **GIVEN** `SandboxTestTool` 已获批准并开始执行
- **WHEN** 沙箱发生技术故障
- **THEN** Incident 进入 `FAILED`；未批准、被拒绝或审批超时才允许不执行并生成受限报告

### Requirement: 失败路径不得自动切换或污染质量分母

系统 MUST 禁止未显式配置的 Provider/Source failover、删减检索步骤以及 Mock/vector-only/关键词/固定结果保底。专门失败注入 Run SHALL 独立标记并排除在 15 次正常质量指标分母之外，但其门禁失败 MUST 阻断阶段发布。

#### Scenario: Provider 技术故障时发生隐藏切换
- **GIVEN** 冻结 Provider 发生技术故障
- **WHEN** 调用账本显示路由到其他 Provider 或替代算法
- **THEN** 技术失败门禁直接为 `FAILED`，报告列出未授权路由，且该结果不得进入质量聚合
