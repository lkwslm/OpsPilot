## Purpose

定义由执行事实和冻结场景规则产生的 Evidence Code 与 Ground Truth，使 Evaluation 只能以结构化精确匹配复算结果，模型与文本相似度均不能影响标准答案。

## Requirements

### Requirement: Evidence Code 由确定性规则生成
Collector MUST 由受版本控制的规则生成 `source.type.fact` Evidence Code；一次命中 MUST 同时满足声明的 Source、Resource、service、UTC 时间窗和 predicate，同一 Evidence 至多命中一个 code，Evaluation 对同一 code 的多个 Artifact MUST 去重。

#### Scenario: 相似文本但服务或时间窗错误
- **GIVEN** 日志文本与目标事实相似，但 service 不匹配或记录时间不在对应窗口
- **WHEN** Evidence Code matcher 执行规则
- **THEN** 不生成目标 code，且不得调用模型或自然语言相似度补判

### Requirement: Ground Truth 仅来源于冻结定义和执行事实
GroundTruthGenerator MUST 只消费冻结 Scenario/Ground Truth 定义、实际注入/恢复事实和通过哈希验证的采集结果，MUST NOT 调用模型、接受 Agent 输出或从 RCA 反推标准答案。

#### Scenario: Agent RCA 与冻结根因冲突
- **GIVEN** Agent 输出的 rootCauseCode 与冻结场景 rootCauseCode 不同
- **WHEN** GroundTruthGenerator 生成本次数据集标准答案
- **THEN** Ground Truth 保持冻结 rootCauseCode 和确定性 Evidence 集合，不受 Agent 输出影响

### Requirement: Ground Truth 写后只读且绑定当前数据集
Ground Truth MUST 校验 `scenarioId/scenarioVersion/rootCauseCode`、required/one-of/forbidden Evidence、Tool 集合、expected action、数据集有效性与恢复 predicate，并与当前 datasetRunId 的执行事实绑定；生成后 MUST 以只读方式保存摘要和内容哈希。

#### Scenario: 跨 Run Ground Truth 混入
- **GIVEN** Ground Truth 引用了另一 datasetRunId 的时间窗或 Artifact
- **WHEN** GroundTruthValidator 执行一致性检查
- **THEN** 校验失败且文件不得进入只读发布状态或被 Evaluation 使用
