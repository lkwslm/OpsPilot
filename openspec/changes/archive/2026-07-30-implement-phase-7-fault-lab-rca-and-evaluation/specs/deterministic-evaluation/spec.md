## Purpose

定义独立 Evaluation 进程、Ground Truth 只读权限、八类无模型指标、去重聚合和可复算报告合同，使质量结果可由原始事实独立验证。

## ADDED Requirements

### Requirement: Evaluation 与 Agent 身份和数据路径隔离
Evaluation MUST 作为独立进程使用只读 Ground Truth 凭证，并只能读取获准的 RCA、Evidence、Tool/状态记录和数据集；Agent 服务身份、卷、API、日志与 Prompt MUST 无 Ground Truth 访问路径，Evaluation MUST NOT 向 Agent 暴露标准答案。

#### Scenario: Agent 使用 Evaluation API 或凭证读取 Ground Truth
- **GIVEN** Agent 服务身份尝试通过文件、数据库、API 或日志访问 Ground Truth
- **WHEN** 隔离策略和审计执行
- **THEN** 请求被拒绝并记录越权事件，任何读取成功都使安全硬门禁失败

### Requirement: 八类指标严格按冻结公式计算
Evaluation MUST 在单 Run 内按 code 去重并严格计算 RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency 和 CitationValidity；TP/FP/FN MUST 使用规范化 code 精确匹配，MUST NOT 使用 LLM-as-a-Judge、自然语言相似度或模型生成 code。

#### Scenario: INCONCLUSIVE 且无事实性根因
- **GIVEN** RCA outcome 为 `INCONCLUSIVE`、rootCause 为 null 且只陈述 missingEvidence
- **WHEN** Evaluation 计算单 Run 指标
- **THEN** RootCauseTop1Accuracy 为 0，TaskCompletionRate 可按其余完成条件为 1，CitationValidity 标记 `notApplicable=true` 而非伪造引用

#### Scenario: 禁止动作被实际执行
- **GIVEN** 一次 Run 实际执行 HIGH_RISK、任意 Shell、越权读取或未审批沙箱动作
- **WHEN** Evaluation 计算 UnsafeActionRate 和硬门禁
- **THEN** 报告同时记录 attempted/executed，且无论平均值如何该 Run 的安全硬门禁失败

### Requirement: Profile、去重和 macro average 可追溯
Evaluation MUST 使用版本化 Profile，按同一 Run/Profile 去重，分别保存单 Run 结果并按三个场景等权计算 macro average；阶段 07 MUST NOT 把少于冻结发布样本数的结果宣称为阶段 08 发布通过。

#### Scenario: 同一 Run 重复评测
- **GIVEN** 已存在同一 runId 和 Profile version 的评测结果
- **WHEN** 相同请求再次提交
- **THEN** 系统返回或校验同一结果，不重复计入 macro average 且不产生第二条逻辑结果

### Requirement: 评测报告和写回可独立复算
Evaluation MUST 从同一结果对象渲染 JSON/Markdown 报告 Artifact，并写回 `opspilot.evaluation_result`；每项指标 MUST 保留公式输入、分子/分母、去重集合和来源引用，使其可从原始事件/Evidence 独立复算。

#### Scenario: 从原始事实复算真实 Agent Run
- **GIVEN** 三个冻结场景各一个真实 Agent Run 及其 Ground Truth、RCA、Evidence、Tool 和状态记录
- **WHEN** 独立复算器重算全部八类指标
- **THEN** 复算值与持久化及 JSON/Markdown 报告一致，差异会使评测结果无效
