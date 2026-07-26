# RAG Knowledge Search

## Purpose
定义基于 Run 快照的精确向量召回、强制 Rerank、KnowledgeReference、Evidence 边界和审计合同。

## Requirements

### Requirement: 查询固定 active revision 并执行受控精确召回
Knowledge Search MUST 使用 Run effective snapshot 固定 collection active revision 和匹配的 Query Embedding revision，对 collection、revision、`searchable=true`、`deleted_at`、ACL、关系字段与允许标签进行过滤，再以 COSINE、INNER_PRODUCT、L2 allowlist 映射的 pgvector operator 执行精确 `candidate_k` 召回。配置或请求文本 MUST NOT 直接拼接 SQL operator。

#### Scenario: 过滤后只召回当前 revision
- **GIVEN** 固定数据集同时包含 active/非 active revision、已删除、不可搜索、ACL 不允许和标签不匹配的 Chunk
- **WHEN** 授权调用方执行带关系与标签过滤的查询
- **THEN** 候选仅来自 Run 快照中的 active revision 且满足全部过滤条件，排序与精确距离基线一致

#### Scenario: 非法距离 operator 在数据库前被拒绝
- **GIVEN** 请求或配置包含 allowlist 外的 operator 或 SQL 片段
- **WHEN** Knowledge Search 构造查询
- **THEN** 输入在访问数据库前被稳定错误拒绝，Repository 未收到拼接语句

### Requirement: 非空候选必须经过真实 Rerank
精确召回产生非空候选时，系统 MUST 调用 Run 快照指定且已验证的 `RerankProvider`，只有完整响应通过合同校验后才能截取最终 `top_k`。Rerank 技术失败或协议失败 MUST 使该知识链路失败；系统 MUST NOT 返回 vector-only、关键词、固定顺序、生成模型排序或替代 Provider 结果。

#### Scenario: 候选存在但 Rerank 不可用
- **GIVEN** pgvector 成功返回至少一个合法候选，但指定 Rerank Provider 超时
- **WHEN** Knowledge Search 执行重排
- **THEN** 任务返回包含 provider/attempt/correlation 的 `ChainFailure`，不生成 MATCH、`top_k` 或 `rerankApplied=true`

### Requirement: 最终结果生成可核验 KnowledgeReference
每个最终候选 MUST 生成稳定 `KnowledgeReference`，至少包含 collection、document、document version、chunk、knowledge/model revision、原始 Artifact、位置、内容哈希、过滤摘要、向量距离、Rerank score/rank 和 Provider identity。引用解析 MUST 校验权限、归属、哈希和 revision，一项不符 MUST fail closed。

#### Scenario: 引用回溯到原始片段
- **GIVEN** 一个成功 MATCH 结果中的 KnowledgeReference
- **WHEN** 授权调用方解析引用
- **THEN** 系统能核验并定位同一 document version 的原始 Artifact 最小片段、Chunk、revision 和完整排序来源，且正文不被复制进引用记录

### Requirement: 知识断言规范化为 Evidence 后才能诊断
Knowledge Search 结果中的可引用断言 MUST 先通过 `EvidenceNormalizer` 转换为不可变 Evidence，并保留 KnowledgeReference 与 Artifact provenance；Diagnosis、Hypothesis 和 RCA MUST NOT 直接消费 Infinity DTO、pgvector 行、原始 Chunk 或未规范化知识断言。

#### Scenario: 绕过 EvidenceNormalizer 被拒绝
- **GIVEN** 一个含有效 KnowledgeReference 但尚未规范化的知识断言
- **WHEN** 调用方尝试直接提交给 Diagnosis 或 Hypothesis
- **THEN** 编译边界、Schema 或合同测试拒绝该路径，只有生成合法 Evidence ID 后才能进入诊断

### Requirement: 完整 RAG 链路保留可审计调用摘要
每次查询 MUST 记录 Run/step/invocation、effective snapshot、Query revision、过滤摘要、`candidate_k`、`top_k`、Embedding 与 Rerank attempts、耗时、Usage/估算和结果引用；记录 MUST 脱敏且不得包含 Secret 或未经授权的完整文档正文。

#### Scenario: MATCH 调用可复算
- **GIVEN** 一次成功 MATCH 的固定查询和数据集
- **WHEN** 检查其审计与性能证据
- **THEN** 证据足以按相同 revision、过滤和 Provider identity 复算候选与最终排序，同时通过 Secret 与正文泄漏扫描
