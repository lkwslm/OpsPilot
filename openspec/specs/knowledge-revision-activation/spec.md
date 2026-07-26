# Knowledge Revision Activation

## Purpose
定义旁路重向量化、原子 revision 切换、Run 快照隔离、显式回切和受保护清理合同，确保并发查询期间没有双 active 或无 active 的可观察窗口。

## Requirements

### Requirement: 重向量化在旁路 revision 完成
重新切分或重向量化 MUST 创建新的 immutable revision，并在不改变当前 active revision 的情况下完成文档/Chunk 条数、coverage、维度、有限值、模型 identity/revision、ACL 与固定抽样检索质量校验。任何校验失败 MUST 保持新 revision 不可搜索。

#### Scenario: 中途失败不影响旧 active revision
- **GIVEN** 旧 active revision 正常提供查询且新 revision 正在旁路向量化
- **WHEN** 新 revision 的一个批次失败或质量校验不通过
- **THEN** 旧 revision 的 active/searchable 状态与固定查询结果不变，新 revision 保持不可搜索并记录 checkpoint 与失败证据

### Requirement: active revision 只在单事务内切换
只有旁路 revision 的全部门禁通过后，系统才 MUST 在单一数据库事务中切换 collection active revision，并同步更新新旧 revision 的 `searchable`。事务任何一步失败 MUST 完整回滚，查询不得观察到两个 active revision 或无 active revision 的中间状态。

#### Scenario: 激活事务注入故障
- **GIVEN** 新 revision 已通过全部门禁且旧 revision 正在服务并发查询
- **WHEN** 在 active 指针与 searchable 更新之间注入数据库故障
- **THEN** 整个事务回滚，并发与后续查询继续只看到旧 revision

### Requirement: 运行中 Run 固定旧 revision 快照
active revision 切换后，已启动 Run MUST 继续使用其 effective snapshot 中的旧 knowledge/model revision，新 Run MUST 使用新 active revision；系统 MUST NOT 在同一 Run 内混用两个 revision 的文档、Query Embedding 或 Rerank identity。

#### Scenario: 切换期间新旧 Run 隔离
- **GIVEN** Run A 在切换前固化旧 revision，切换后创建 Run B
- **WHEN** 两个 Run 并发执行相同 Knowledge Search
- **THEN** Run A 全链路使用旧 revision，Run B 全链路使用新 revision，各自引用和审计无跨 revision 混合

### Requirement: 显式回切可恢复旧结果
保留窗口内的旧 revision MUST 支持经权限校验和审计的显式原子回切；回切 MUST 执行与正向激活相同的 coverage、identity 与引用完整性校验。成功后固定查询 MUST 恢复旧结果，运行中 Run 仍遵循自身快照。

#### Scenario: 回切到受保护旧 revision
- **GIVEN** 旧 revision 在保留窗口内且全部 Artifact/向量引用完整
- **WHEN** 授权操作员执行显式回切
- **THEN** collection 原子恢复旧 active/searchable 状态，固定新 Run 查询恢复旧结果并生成审计记录

### Requirement: 未激活 revision 清理受引用与保留策略保护
清理命令 MUST 只选择未激活、超过保留条件且未被 Run、KnowledgeReference、Evidence 或受保护 Artifact 引用的 revision；清理 MUST 可重入、可审计，并 MUST NOT 自动删除 active 或可回切 revision。

#### Scenario: 历史引用阻止清理
- **GIVEN** 一个未激活 revision 已超过普通保留时间但仍被历史 KnowledgeReference 或 Evidence 引用
- **WHEN** 执行清理命令
- **THEN** 该 revision 及其受保护 Artifact/向量不被删除，命令记录跳过原因且可安全再次执行
