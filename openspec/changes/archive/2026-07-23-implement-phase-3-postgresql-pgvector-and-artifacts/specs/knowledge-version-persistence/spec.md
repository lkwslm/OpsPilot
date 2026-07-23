## ADDED Requirements

### Requirement: 可恢复的知识版本与 checkpoint 原语
系统 MUST 提供 Knowledge Document、Version、Chunk、ingestion job/checkpoint 的创建、读取和追加更新原语，并持久化 coverage、失败 Chunk、job attempts 与 `last_error`；失败 MUST NOT 静默丢失 Chunk。

#### Scenario: 导入任务中途失败后恢复
- **GIVEN** ingestion job 已保存部分 coverage、失败 Chunk 和 checkpoint
- **WHEN** 进程中止后重新加载 job
- **THEN** Repository 恢复 attempts、最后错误、已完成与待处理 Chunk，且不会把未完成覆盖标记为完整

### Requirement: 内容哈希复用受 revision 限制
相同 revision 下内容哈希匹配时 MAY 复用已有计算结果，但新 Chunk MUST 插入独立向量行并重新校验内容哈希和维度；系统 MUST NOT 跨 revision 复用向量。

#### Scenario: 同 revision 复用与跨 revision 拒绝
- **GIVEN** 已存在一个内容哈希及其 revision 向量
- **WHEN** 新 Chunk 分别在相同 revision 和不同 revision 请求复用
- **THEN** 相同 revision 创建经重新校验的独立向量行，不同 revision 被拒绝或重新计算且不引用旧向量行

### Requirement: coverage 门禁与原子 active revision 切换
只有 coverage 状态证明完整时，系统 MUST 在同一事务中切换 document version/collection active revision，并同步设置新旧 revision 的 `searchable`；任一步失败 MUST 保持旧 revision 完整可用。

#### Scenario: 覆盖不完整不能切换
- **GIVEN** 新 revision 仍有失败或缺失 Chunk
- **WHEN** 请求将其设为 active 与 searchable
- **THEN** 事务被拒绝，旧 active revision 与 searchable 状态不变

#### Scenario: 切换事务中途失败
- **GIVEN** 新 revision coverage 完整且旧 revision 正在提供查询
- **WHEN** 在 active 与 searchable 更新之间注入故障
- **THEN** 整个事务回滚，固定查询继续返回旧 revision 结果

### Requirement: 删除过滤、保留窗口与回滚
带 `deleted_at` 的文档或 Chunk MUST 立即从所有检索入口过滤；旧 revision MUST 在保留窗口内支持原子回滚，回滚后固定查询 MUST 恢复旧结果。

#### Scenario: 回滚到保留的旧 revision
- **GIVEN** 新 revision 已激活且旧 revision 仍在保留窗口内
- **WHEN** 执行回滚事务
- **THEN** 旧 revision 恢复 active/searchable，新 revision 不再对正常查询可见且结果与切换前固定数据集一致

### Requirement: 阶段 03 不编排 Provider
本能力 MUST 只提供 Repository、数据库约束和事务原语，MUST NOT 调用 Embedding/Rerank Provider 或编排导入、更新、删除、质量验证与旁路重向量化流程。

#### Scenario: 生产装配范围检查
- **GIVEN** 阶段 03 的知识持久化模块和 composition root
- **WHEN** 架构测试扫描 Provider 调用与导入编排实现
- **THEN** 只存在持久化 Port 实现和事务原语，不存在阶段 05 才负责的 Provider 工作流
