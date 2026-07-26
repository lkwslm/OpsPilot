## ADDED Requirements

### Requirement: 文档导入保存原始 Artifact 与版本化切分结果
系统 MUST 对允许格式执行显式校验和规范化，先将原始文档保存为受控 Artifact，再按版本化切分策略创建 document version、Chunk、metadata 与 ACL。每个 Chunk MUST 可追溯到文档、版本、Artifact、位置和内容哈希，未经授权的内容不得进入向量化或检索。

#### Scenario: 合法文档导入可完整追溯
- **GIVEN** 一个格式受支持且调用方有写权限的文档
- **WHEN** 执行导入与切分
- **THEN** 原始 Artifact、document version、Chunk、位置、内容哈希、metadata、ACL 和切分策略版本形成完整引用链

#### Scenario: 格式或 ACL 无效时 fail closed
- **GIVEN** 文档格式不在 allowlist 或 ACL metadata 不合法
- **WHEN** 请求导入
- **THEN** 系统在调用 Embedding 前拒绝请求，不创建可搜索 revision，也不留下无引用的可用 Artifact

### Requirement: knowledge revision 不可变且未完成时不可见
每次导入、更新或重向量化 MUST 创建 immutable knowledge revision，并记录文本哈希、Embedding model revision、维度、预处理合同、coverage 与 job 状态。存在失败或缺失 Chunk 的 revision MUST 可从 checkpoint 恢复，但 MUST NOT 标记 active/searchable 或对查询可见。

#### Scenario: 向量化中途失败后恢复
- **GIVEN** 新 revision 已完成部分批次并保存 checkpoint，后续批次失败
- **WHEN** 进程恢复该 ingestion job
- **THEN** 系统从已验证 checkpoint 继续，不重复提交完整批次，不丢失失败 Chunk，且该 revision 在完成前不可搜索

### Requirement: 导入更新幂等且批次提交原子
相同幂等键和请求哈希的重复导入 MUST 返回同一 job/document version 结果；相同键不同哈希 MUST 冲突。Embedding 批次 MUST 在向量与 coverage 更新的同一事务边界内原子提交，失败不得产生半批可见结果。

#### Scenario: 重复导入不会复制版本
- **GIVEN** 一个已接受的导入请求及其幂等键和请求哈希
- **WHEN** 客户端以相同键和相同内容重试
- **THEN** 系统返回原 job 与 document version，不重复创建 Chunk、向量或 Artifact

### Requirement: 删除立即退出检索并保护历史引用
删除文档 MUST 先设置 `deleted_at`，所有检索入口 MUST 立即过滤该文档及其 Chunk。后续异步清理 MUST 遵守 Artifact 保留策略与历史 Run、Evidence、KnowledgeReference 的引用保护，不得破坏仍受保护的回溯。

#### Scenario: 删除与历史引用并存
- **GIVEN** 一个被当前 active revision 检索且已被历史 Run 引用的文档
- **WHEN** 执行删除并随后运行清理任务
- **THEN** 新查询立即不再返回该文档，历史引用在保留期内仍可核验，受保护 Artifact 与 revision 不被物理删除

### Requirement: 文档更新创建新版本而非覆盖历史内容
文档更新 MUST 创建新的 document version、Chunk 和目标 knowledge revision，不得覆盖既有版本、向量、Artifact 或引用。只有新 revision 完成质量与 coverage 门禁并被显式激活后，新内容才能成为正常查询结果。

#### Scenario: 更新失败保留旧版本服务
- **GIVEN** 旧 document version 位于 active revision
- **WHEN** 新版本导入或向量化失败
- **THEN** 旧 active revision 与历史引用保持不变，新版本保持不可搜索并可从 checkpoint 恢复
