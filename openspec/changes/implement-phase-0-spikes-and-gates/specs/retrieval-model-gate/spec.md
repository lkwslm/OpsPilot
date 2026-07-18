## ADDED Requirements

### Requirement: 同实例双模型身份
一个锁定 digest 的 Infinity 实例 MUST（必须）加载准确 revision 的真实 Embedding 与 Rerank 模型，`/models` 必须返回预期 served name、模型 ID 和加载能力。

#### Scenario: 模型身份与版本锁一致
- **GIVEN** 版本锁中的 Infinity digest 和两个不可变模型 revision
- **WHEN** 启动实例并查询 `/models`
- **THEN** 返回的 served name、模型 ID 和能力与版本锁完全一致，否则探针失败

### Requirement: Embedding 数值正确性
探针 MUST（必须）对固定文本至少执行三次 Embedding，验证数量、顺序、实际维度、有限值、归一化约定及 COSINE 所需非零范数。

#### Scenario: 三次向量结果满足合同
- **GIVEN** 固定顺序的文本集合
- **WHEN** 连续执行至少三次真实 Embedding
- **THEN** 每次结果数量、顺序和维度一致，所有数值有限并符合归一化约定且范数非零

### Requirement: Rerank 排序与并发隔离
探针 MUST（必须）对固定正负文档执行真实 Rerank，校验原 index 完整、分数有限、正样本排序及响应 identity；并发 Embedding/Rerank 不得串模型。

#### Scenario: 并发调用不串模型
- **GIVEN** 固定的 Embedding 文本和 Rerank 正负样本
- **WHEN** 并发调用两个模型
- **THEN** 每个响应保持正确模型 identity、输入映射和有效数值，正样本排名满足预期

### Requirement: 质量和延迟冻结门禁
固定中文查询集上的 Rerank 后 NDCG@10 与 MRR MUST（必须）均不低于仅向量基线，且至少一项相对提升不低于 5%；最小真实 `Embedding → 精确召回 → Rerank` 链路 p95 必须小于 2 秒。

#### Scenario: 选型达到质量与性能阈值
- **GIVEN** 固定查询集、精确向量基线和固定开发机规格
- **WHEN** 执行完整质量与延迟基准
- **THEN** NDCG@10、MRR、至少一项提升比例及 p95 同时满足冻结阈值，否则门禁失败

### Requirement: 可复现的模型选型证据
探针 JSON MUST（必须）通过 Schema，记录镜像 digest、不可变 revision、模型/权重来源、License、机器规格、CPU/内存/GPU、冷/热启动、最大长度、并发干扰、中文效果、探针版本和 UTC 时间；任一断言失败时一次性容器必须非零退出。

#### Scenario: 探针报告完整且不可伪造
- **GIVEN** 一次真实模型探针和基准执行
- **WHEN** 生成版本化结果 JSON
- **THEN** 报告通过 Schema 且包含全部供应链、资源、质量、性能和身份字段，并能关联到 commit 与版本锁
