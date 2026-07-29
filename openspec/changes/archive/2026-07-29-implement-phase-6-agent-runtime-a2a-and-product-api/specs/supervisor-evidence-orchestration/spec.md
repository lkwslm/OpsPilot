## ADDED Requirements

### Requirement: Supervisor 按权威状态串行委派
Supervisor MUST 按 Run/step 状态机和计划顺序串行委派专业 Agent，只能显式跳过无适用代码 revision 的 CodeAnalysis，并仅在新增 Evidence 可能改变结论时进行有界补证回环；专业 Agent MUST NOT 相互发现或级联委派。

#### Scenario: 专业 Agent 不能委派同伴
- **GIVEN** Diagnosis Agent 输出调用 Knowledge skill 的建议
- **WHEN** 建议进入能力与委派策略
- **THEN** 专业服务不发起 A2A 调用，只把缺失 Evidence 需求作为 Artifact 返回 Supervisor 决策

### Requirement: 委派请求先于网络持久化
Supervisor MUST 在每次 A2A 调用前持久化 step/attempt/messageId、目标 skill、输入 Evidence/Artifact 引用和 effective capability snapshot，事务提交后才能发送请求。

#### Scenario: 未提交委派不得出网
- **GIVEN** 保存 step/messageId 的事务被注入失败
- **WHEN** Supervisor 尝试委派专业 Agent
- **THEN** 网络调用次数为零，Run 保留可恢复失败/checkpoint 且无孤立远端 Task

### Requirement: Artifact 按固定顺序分层校验
Supervisor MUST 严格按“媒体类型 → major schema version → JSON Schema → Source/Resource/Task/Run 归属 → Artifact SHA-256 → 引用权限 → 领域不变量”校验远端 Artifact；前一步失败 MUST 短路后续领域处理。

#### Scenario: 每层非法 Artifact 均被拒绝
- **GIVEN** 分别在八个校验层构造唯一错误的 Artifact fixture
- **WHEN** Artifact receiver 逐个处理
- **THEN** 每个 fixture 在对应层失败，错误稳定可审计，领域表和 outbox 均无部分写入

### Requirement: Artifact 与领域事实单事务接收
只有全部校验通过后，系统才 MUST 在单事务中保存 Artifact 引用、Evidence/Hypothesis/关系/验证结果及 outbox；Diagnosis/Hypothesis MUST 只引用已归属当前 Run 的 Evidence ID，未规范化 Observation、CodeFinding 或 KnowledgeResult MUST NOT 成为事实。

#### Scenario: outbox 写入失败整体回滚
- **GIVEN** 一个完整合法 Artifact 且 outbox 插入被注入失败
- **WHEN** 执行接收事务
- **THEN** Artifact 接收标记、Evidence/Hypothesis/关系和 outbox 全部不提交，重试可安全执行一次

### Requirement: analysis seal 原子冻结分析数据
进入 `GENERATING_REPORT` 前，系统 MUST 确认所有计划内 attempt 已终止或登记 `missingEvidence`、所有已接收 Artifact 已完成领域事务，然后原子递增 `runVersion` 并设置 `analysisSealedAt`；封账后 MUST 拒绝该 Run 的 Evidence、Hypothesis、关系和验证结果新增或修改。

#### Scenario: 迟到 Artifact 遇到封账
- **GIVEN** Run 已完成 analysis seal 且一个旧 attempt 的 Artifact 迟到
- **WHEN** receiver 尝试写入领域事实
- **THEN** 写入以稳定封账冲突拒绝，既有 runVersion 和报告输入不变，协议审计仍保留迟到事件

### Requirement: 唯一结构化 RCA 从封账数据直接生成
`RcaReportService` MUST 在短只读一致性事务中按 `runId + runVersion` 读取全部 Evidence、Hypothesis、支持/冲突关系、验证结果和 `missingEvidence`，事务外生成并校验唯一结构化 RCA，再从同一对象渲染 JSON/Markdown；MUST NOT 建立第二份 RCA 输入快照表或在模型调用期间持有事务。

#### Scenario: 报告失败使用相同数据重试
- **GIVEN** Run 已封账且第一次模型生成报告失败
- **WHEN** 使用同一 runId/runVersion 重试
- **THEN** 两次读取的结构化输入 digest 相同，不复制新快照或接收新事实，成功后 JSON/Markdown 引用同一 RCA 对象

### Requirement: 补证必须受 Evidence 与预算约束
Supervisor 的补证请求 MUST 明确缺失事实、允许的 Agent/Tool、剩余预算、deadline 和最大回环次数；没有新 Evidence、重复动作、预算耗尽或 `NO_PROGRESS` 时 MUST 停止委派并登记受限结论所需的 `missingEvidence`。

#### Scenario: 重复补证停止
- **GIVEN** 连续两次专业返回相同 Evidence 集合和动作指纹
- **WHEN** Supervisor 评估第三次补证
- **THEN** 不再创建新 attempt，保存 `NO_PROGRESS` 与 missingEvidence，并按状态机进入受限报告路径

### Requirement: 知识空结果与技术失败严格分流
Supervisor MUST 将 `KB_EMPTY` 和 `NO_MATCH` 视为成功但无知识 Evidence 的可区分结果并继续现场调查；LLM、A2A、Tool、Embedding、Rerank、数据库或 Artifact 校验技术失败 MUST 按冻结状态矩阵结束对应 attempt，或仅在策略明确允许时登记具体 `missingEvidence`，MUST NOT 伪装为完成、空结果或 Evidence。

#### Scenario: Rerank 失败不能变成 NO_MATCH
- **GIVEN** Knowledge Agent 已获得非空候选但 Rerank 调用技术失败
- **WHEN** Supervisor 接收该 Task 的失败状态和限制说明
- **THEN** attempt 以对应 ChainFailure 收敛或登记明确 missingEvidence，不产生 `NO_MATCH`、成功 Evidence 或虚假完成状态
