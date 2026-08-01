## Purpose

该能力用于证明知识检索或历史案例合法为空时，系统仍按业务空结果完成现场调查、形成受限结论，并严格禁止把空结果误报为技术故障或伪造引用。

## ADDED Requirements

### Requirement: 正常空结果必须与技术失败严格区分

系统 SHALL 分别验证 `KB_EMPTY`、真实 `NO_MATCH` 和历史案例为 0。三者 MUST 作为正常业务结果而非 `ChainFailure`；`KB_EMPTY` 与真实 `NO_MATCH` 不得触发 Rerank，且不得用关键词、固定候选或其他 Provider 补造结果。

#### Scenario: 空知识库
- **GIVEN** 知识 collection 存在但没有 active Chunk
- **WHEN** KnowledgeAgent 执行检索
- **THEN** 系统返回结构化 `KB_EMPTY`，不调用 Rerank、不记录技术失败，并继续现场 Evidence 调查

#### Scenario: 真实检索无匹配
- **GIVEN** active Chunk 存在但固定真实检索产生零候选
- **WHEN** KnowledgeAgent 完成检索
- **THEN** 系统返回结构化 `NO_MATCH`，不调用 Rerank且不生成伪造 Citation

#### Scenario: 无历史案例
- **GIVEN** 现场 Evidence 可用但历史案例查询结果为 0
- **WHEN** Supervisor 完成调查
- **THEN** 系统保留零历史事实并继续生成受限 RCA，不将其转换为依赖故障

### Requirement: 空结果调查必须在预算内形成真实受限结论

系统 SHALL 在冻结的轮数、Tool/A2A、时长和 Token 预算内继续现场调查，并输出带真实 Evidence、有效 Citation、limitations 与 `missingEvidence` 的 `CONCLUSIVE`、`PARTIAL` 或 `INCONCLUSIVE`。无足够根因证据时 `rootCause` MUST 为空；不得用没有引用的事实性陈述伪装确定结论。

#### Scenario: 没有可引用的根因证据
- **GIVEN** Knowledge 结果为空且现场调查未获得足够根因证据
- **WHEN** 系统生成 RCA
- **THEN** RCA 为受限 `INCONCLUSIVE`、`rootCause=null`，明确列出 `missingEvidence`，不伪造 Citation，CitationValidity 标记为合法的 `notApplicable`

#### Scenario: 现场证据足以形成部分结论
- **GIVEN** Knowledge 结果为空但现场 Evidence 支持有限范围的假设
- **WHEN** 系统生成 RCA 与 Evaluation
- **THEN** 报告以 `PARTIAL` 或有充分依据的 `CONCLUSIVE` 表达结论，引用全部属于当前 Run 且明确说明知识缺口
