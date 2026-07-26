## ADDED Requirements

### Requirement: Embedding 批处理同时受 Token 与 Provider 限制
Infinity Embedding Adapter MUST 按 Token 数、最大条数和 Provider 限制切分批次，并保持每批输入输出条数与顺序一致。单批只有在全部向量通过响应校验后才能原子提交；部分响应、部分失败或顺序不可证明 MUST 使整批失败且保存 job checkpoint。

#### Scenario: 单批部分响应不写入
- **GIVEN** 一个包含多个 Chunk 的 Embedding 批次
- **WHEN** Infinity 只返回部分向量或返回数量与输入不一致
- **THEN** 整批不写入任何新向量，job checkpoint 记录该批次和可重试分类，后续恢复不会跳过缺失 Chunk

#### Scenario: Token 限制优先切批
- **GIVEN** 输入条数未超过 batch size 但合计 Token 超过 Provider 上限
- **WHEN** batcher 规划请求
- **THEN** 输入按版本化 Token 计算规则拆为合法批次且全局顺序保持不变，不在 Adapter 内截断超长文本

### Requirement: 向量数值维度与 identity fail closed
Adapter MUST 核对锁定的 provider、model、model revision、实际 dimension、预处理与距离合同，并拒绝空向量、数量/长度不一致、NaN、Infinity、维度漂移以及 COSINE 零范数。Adapter MUST NOT 截断、补零或自动转换维度。

#### Scenario: revision 维度漂移
- **GIVEN** capability snapshot 将某 model revision 的维度冻结为指定值
- **WHEN** Infinity 为任一输入返回不同维度或非有限数值
- **THEN** 整批以稳定合同错误失败，不写入向量，也不修改既有 revision 的维度

### Requirement: Embedding 复用只限同 revision 内容哈希
系统 MUST 使用规范化文本哈希与 `model_revision_id` 判定是否复用计算结果；相同 revision 复用时仍 MUST 为新 Chunk 写入独立向量行并重新校验，跨 revision MUST 重新计算且不得引用旧向量行。

#### Scenario: 相同文本跨 revision 导入
- **GIVEN** 同一规范化文本已在旧 model revision 下存在向量
- **WHEN** 新 knowledge revision 使用不同 model revision 导入该文本
- **THEN** 系统调用新 revision 的真实 Embedding，不复用旧向量或改变旧记录

### Requirement: Query 与文档使用完全一致的 Embedding revision
Knowledge Query MUST 使用 Run 快照中 active document revision 对应的 model revision、预处理、dimension、normalization 和 distance metric。所需 Provider 不可用或任一属性不匹配时 MUST 明确失败，且不得调用其他模型或维度作为替代。

#### Scenario: 查询 revision 不匹配
- **GIVEN** Run 快照指向 revision A，但请求解析到 revision B 的 Query Embedding profile
- **WHEN** Knowledge Search 准备向量查询
- **THEN** 请求在访问 pgvector 前以 revision mismatch 失败，不执行跨 revision 检索或自动 Provider 切换

### Requirement: Embedding 真实探针冻结能力证据
启动探针 MUST 对固定顺序文本执行真实 Embedding，验证模型 identity/revision、数量、顺序、维度、有限值、归一化约定和 COSINE 非零范数，并把镜像 digest、模型 revision、License 与资源信息关联到 capability snapshot。

#### Scenario: 真实维度与配置不一致
- **GIVEN** 配置声明的维度与锁定 Infinity 模型实际返回维度不同
- **WHEN** 执行启动能力探针
- **THEN** Embedding Provider 不进入 Registry 可用能力，readiness 为 DOWN 且报告不泄露凭据或完整输入
