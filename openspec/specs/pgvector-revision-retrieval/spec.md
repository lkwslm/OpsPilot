# pgvector-revision-retrieval Specification

## Purpose

定义 pgvector 的 revision、维度、数值安全和精确 Top-K 检索合同，确保知识内容重建索引后可追溯、错误向量被拒绝，并在限定 revision 内产生稳定且隔离的检索结果。

## Requirements

### Requirement: revision 与向量维度双重约束
知识向量 MUST 使用无 typmod `vector`，通过 `(model_revision_id, embedding_dimension)` 复合外键和 `vector_dims` 双重校验真实维度。distance metric 与 normalization MUST 是 revision 的不可变属性，query revision 与维度 MUST 完全匹配。

#### Scenario: 维度漂移或跨 revision 写入
- **GIVEN** 一个已冻结维度、度量和归一化属性的 model revision
- **WHEN** 写入维度不符的向量或使用另一个 revision 查询
- **THEN** 应用或数据库拒绝操作，不保存或返回跨 revision 结果

### Requirement: 非法数值与 COSINE 零范数双层拒绝
应用层 MUST 拒绝包含 NaN 或 Infinity 的向量；COSINE revision MUST 在应用与数据库约束或触发器两层拒绝零范数向量。

#### Scenario: 非有限值和零向量
- **GIVEN** NaN、Infinity 或 COSINE 零范数候选向量
- **WHEN** 分别绕过或通过应用写入 PostgreSQL
- **THEN** 每种非法向量都在应用或数据库边界被拒绝且表中无残留记录

### Requirement: 受控精确 Top-K 检索
Top-K 查询 MUST 同时过滤 collection、active revision、`searchable=true`、未删除文档状态和允许的元数据条件；距离运算符 MUST 只由 COSINE、INNER_PRODUCT、L2 allowlist 映射，查询参数、动态维度和配置文本 MUST NOT 直接拼接 SQL。

#### Scenario: 合法 revision 的可预测排序
- **GIVEN** 一个包含已知向量距离、活动与非活动 revision、可搜索与不可搜索 Chunk 的固定数据集
- **WHEN** 使用匹配 revision、维度和合法 metric 执行 Top-K
- **THEN** 只返回满足全部过滤条件的结果且排序与预期距离一致

#### Scenario: 非法 operator 注入
- **GIVEN** 请求或配置提供不在 allowlist 的 operator 或 SQL 片段
- **WHEN** 构造向量查询
- **THEN** Repository 在执行 SQL 前拒绝输入且数据库未收到拼接后的语句

### Requirement: MVP 禁止默认 ANN
当 collection 少于 50,000 个 active Chunk 时，系统 MUST 使用过滤后的精确扫描并 MUST NOT 在默认 migration 中创建 HNSW 或 IVFFlat 索引；超过门槛只允许触发独立基准与设计流程。

#### Scenario: Schema ANN 检查
- **GIVEN** 阶段 03 的全部 migration 已应用且 active Chunk 基线小于 50,000
- **WHEN** 检查 PostgreSQL 索引定义并执行检索基线
- **THEN** 不存在 HNSW/IVFFlat 索引且精确检索满足固定结果集
