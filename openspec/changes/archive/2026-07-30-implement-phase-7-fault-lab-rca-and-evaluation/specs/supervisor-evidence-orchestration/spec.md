## MODIFIED Requirements

### Requirement: 唯一结构化 RCA 从封账数据直接生成
`RcaReportService` MUST 只为状态为 `GENERATING_REPORT` 且已完成 analysis seal 的 Run 生成报告，在短只读 `REPEATABLE READ` 事务中按 `runId + runVersion` 读取全部 Evidence、Hypothesis、支持/冲突关系、验证结果和 `missingEvidence`，校验 `runVersion/analysisSealedAt` 后结束事务，再在事务外生成并校验唯一 `rca.schema.json` 领域对象；MUST NOT 建立第二份 RCA 输入快照表、在模型调用期间持有事务或接受封账后的新事实。

#### Scenario: 报告失败使用相同封账数据重试
- **GIVEN** Run 已封账且第一次模型生成报告失败
- **WHEN** 使用同一 runId/runVersion 重试
- **THEN** 两次读取的结构化输入 digest 相同，不复制新快照或接收新事实，模型调用均不持有数据库事务

#### Scenario: 未封账 Run 请求报告
- **GIVEN** Run 尚未进入 `GENERATING_REPORT` 或缺少 `analysisSealedAt`
- **WHEN** 请求生成 RCA
- **THEN** 服务以稳定状态冲突拒绝，不执行报告模型调用或写入 RCA 元数据

## ADDED Requirements

### Requirement: RCA 完整表达证据与受限结论
结构化 RCA MUST 包含多个 Hypothesis、支持/冲突 Evidence、验证结果、结论等级、修复建议、限制和 `missingEvidence`；所有引用 MUST 属于当前 Run 且 Evidence/Artifact 可访问、哈希有效，证据不足时 MUST 允许 `PARTIAL`，或输出 `INCONCLUSIVE` 且 `rootCause=null`，MUST NOT 伪造确定根因。

#### Scenario: Evidence 不足形成受限报告
- **GIVEN** 当前 Run 的必需 Evidence 缺失且剩余事实不能支持唯一根因
- **WHEN** 生成并校验 RCA
- **THEN** 报告输出 `INCONCLUSIVE/rootCause=null` 或有依据的 `PARTIAL`，列出 missingEvidence 和限制，不创建无引用的确定性根因

### Requirement: RCA 引用严格绑定当前 Run
每个 Citation MUST 校验对象存在、属于当前 Run、调用方可访问、Artifact SHA-256 有效、Evidence 时间窗与 incident window 重叠，且 Claim 声明的 evidenceCode 与 Evidence 记录一致；任一引用失败 MUST 阻止报告就绪。

#### Scenario: 跨 Run Evidence 引用
- **GIVEN** RCA 候选对象引用另一个 Run 中存在且哈希有效的 Evidence
- **WHEN** CitationValidator 校验报告
- **THEN** 引用以跨 Run 错误失败，RCA JSON/Markdown 均不得发布为就绪

### Requirement: RCA 元数据和双格式保持同源
系统 MUST 保存与 `runId + runVersion` 绑定的唯一 RCA 元数据，并且只从同一个通过 Schema 的 RCA 对象渲染 JSON 和 Markdown；两种格式 MUST 具有一致的结论、Hypothesis、Evidence 引用、动作、限制与生成版本。

#### Scenario: 双格式语义一致性校验
- **GIVEN** 一个已通过 Schema 和引用校验的 RCA 对象
- **WHEN** 系统生成 JSON 与 Markdown Artifact
- **THEN** 两者的规范化语义摘要和来源对象 digest 一致，任一 renderer 失败都不把报告标记为完整
