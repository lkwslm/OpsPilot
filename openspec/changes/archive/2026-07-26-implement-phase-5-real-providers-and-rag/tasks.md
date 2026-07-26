## 1. 05-WP01：三类 Provider Registry 与配置快照

- [x] 1.1 `05-WP01.T1` 在 `opspilot-core` 定义 Chat、Embedding、Rerank 三个专用 Registry、稳定 capability key、required/协议版本约束和冻结状态，不引入万能 Registry 或 classpath 扫描。
- [x] 1.2 `05-WP01.T1` 在 `opspilot-server` composition root 显式装配三类 Registry，并用 `./mvnw -pl opspilot-core,opspilot-server -am test` 验证重复 ID、required 缺失、版本不兼容、冻结后修改和确定性错误。
- [x] 1.3 `05-WP01.T2` 定义模型默认配置、六角色稀疏覆盖、任务白名单覆盖、非敏感 `model_profile`/版本 Mapper 和字段来源记录，保持 `AgentProfile.model.modelProfileRef` 只引用逻辑 Profile。
- [x] 1.4 `05-WP01.T2` 扩展 PostgreSQL migration/Repository 与 Run 创建流程，持久化去密钥 effective model/knowledge snapshot 和配置版本；测试覆盖继承优先级、非法覆盖、热更新隔离和旧数据兼容。
- [x] 1.5 `05-WP01.T3` 实现受支持 scheme 的 `SecretResolver`、配置字段级校验、去密钥 capability report/digest 及调用边界脱敏器，确保模型名、Key 或上下文上限无虚假默认值。
- [x] 1.6 `05-WP01.T3` 实现“静态校验 → 去重真实探针 → capability snapshot → Registry 冻结”的启动顺序及 liveness/readiness 分流，配置缺失非零退出、临时不可达仅 readiness DOWN。
- [x] 1.7 `05-WP01.T3` 执行 Registry/配置/快照合同测试与 `python scripts/security/scan_repository.py`，把测试摘要、capability digest、配置失败样例和 Secret 泄漏扫描写入 `outputs/phase5/05-WP01/`。

## 2. 05-WP02：真实 OpenAI-Compatible Chat Provider

- [x] 2.1 `05-WP02.T1` 在 `opspilot-adapters/model-openai-compatible` 实现通用 HTTP client、Jackson DTO/领域 Mapper 与 URI 组合器；默认 base URL 为 `https://api.deepseek.com`，模型和 Key 为空，资源路径不重复拼 `/v1`。
- [x] 2.2 `05-WP02.T1` 添加 URI、默认值、header 注入和脱敏单元测试，并用 `./mvnw -pl opspilot-adapters/model-openai-compatible -am test` 验证兼容 base URL 组合与 Secret 不进入序列化结果。
- [x] 2.3 `05-WP02.T2` 实现普通与流式 Chat 请求，规范化消息、finish reason、实际 identity、Usage 和完整结束语义；流式中断、协议无效或取消不得提交半成品最终消息。
- [x] 2.4 `05-WP02.T2` 实现工具调用、JSON Schema/严格 JSON 模式、最多一次受控结构修复、取消传播和 400/401/403/404/429/5xx/网络/Schema/流中断的稳定 `ChainFailure` 映射，Adapter 内不重试、不切 Provider。
- [x] 2.5 `05-WP02.T2` 建立 Chat 共享合同套件，覆盖普通、流式、工具、结构化、Usage 缺失、取消、错误关联 ID 和日志脱敏；运行模块测试并输出 `outputs/phase5/05-WP02/chat-contract-report.json`。
- [x] 2.6 `05-WP02.T3` 实现模型 identity、普通 Chat、工具调用、结构化输出和 profile 所需流式的逐项真实探针，将配置版本、UTC、Usage 和脱敏结论写入 capability snapshot。
- [x] 2.7 `05-WP02.T3` 使用受保护 Secret 对锁定 DeepSeek-compatible 端点执行真实探针与抓包脱敏验证，输出 `outputs/phase5/05-WP02/` 证据；任一 required 能力缺失时保持 readiness DOWN，不用 Fake 代替。

## 3. 05-WP03：Context、预算、deadline、重试与 bulkhead

- [x] 3.1 `05-WP03.T1` 在 `opspilot-core` 实现版本化公共/角色/动态 Prompt 与 `AgentContextBuilder`，只装配当前任务、必要状态、允许 Tool Schema、Evidence 引用和受权 Artifact 最小片段。
- [x] 3.2 `05-WP03.T1` 实现 `ContextCompactor` 的冻结压缩顺序和保留集合，用单元测试证明重复工具输出、已完成细节、低相关候选依次压缩且不采用最早消息截断。
- [x] 3.3 `05-WP03.T2` 实现 `TokenBudgetManager` 与 Token/调用数/可选成本账本，支持调用前预留、Provider Usage 对账、版本化保守估算、价格表版本和失败 attempt 入账。
- [x] 3.4 `05-WP03.T2` 增加账本持久化/审计映射及测试，覆盖 Usage 完整/缺失、缓存 Token、失败/取消、重试、金额不可用和并发预留冲突；输出 `outputs/phase5/05-WP03/usage-ledger-report.json`。
- [x] 3.5 `05-WP03.T3` 实现 Incident、step、Provider/Tool request、connect/read 四层 deadline 派生，以及每个 Provider Profile 独立 semaphore/bulkhead、等待计时、取消 HTTP 请求和 `finally` 释放 permit。
- [x] 3.6 `05-WP03.T3` 用可控时钟和并发测试覆盖父 deadline 截止、semaphore 等待计入、取消释放、Profile 隔离、迟到响应丢弃和无数据库长事务；运行 `./mvnw -pl opspilot-core,opspilot-server -am test`。
- [x] 3.7 `05-WP03.T4` 实现共享重试分类器：三类 Provider 默认总 attempt 不超过 2，401/403/模型不存在不重试，400/Schema/上下文超限不原样重放，429 受控 `Retry-After`，网络/502/503/504 在父 deadline 内指数退避加 jitter。
- [x] 3.8 `05-WP03.T4` 添加逐错误类型、attempt 上限、幂等 invocation ID、退避裁剪、结构修复最多一次和禁止自动 failover 测试，输出 `outputs/phase5/05-WP03/retry-deadline-report.json`。
- [x] 3.9 `05-WP03.T5` 实现预算不足时“压缩 → 减知识候选 → 缩代码/时间窗 → 拆子任务 → Supervisor 重规划”的恢复策略，仍不足返回 `TOKEN_BUDGET_EXCEEDED` 并原子保存 checkpoint、缺失工作和剩余预算。
- [x] 3.10 `05-WP03.T5` 验证预算耗尽停止新调用、失败调用仍计费、恢复状态可重载及 `GENERATING_REPORT` 映射，输出 `outputs/phase5/05-WP03/budget-exhaustion-state-report.json`。

## 4. 05-WP04：Infinity Embedding Adapter

- [x] 4.1 `05-WP04.T1` 在 `opspilot-adapters/retrieval-infinity` 实现 Infinity Embedding HTTP Adapter 与按 Token、最大条数和 Provider 限制切批的 batcher，保持全局输入输出条数和顺序。
- [x] 4.2 `05-WP04.T1` 把单批响应校验、向量写入和 coverage/checkpoint 更新置于原子提交边界；测试部分响应、乱序、单项失败、超长 Chunk 和进程恢复均不产生半批结果。
- [x] 4.3 `05-WP04.T2` 实现 provider/model/revision/dimension/normalization/distance identity 核对和向量 validator，拒绝空值、NaN、Infinity、维度漂移及 COSINE 零范数，禁止截断、补零和自动改维度。
- [x] 4.4 `05-WP04.T2` 实现真实 Embedding capability probe，并运行共享合同与固定文本三次探针，输出实际维度、模型 revision、镜像 digest、License 和资源证据到 `outputs/phase5/05-WP04/`。
- [x] 4.5 `05-WP04.T3` 实现规范化文本哈希与 `model_revision_id` 缓存复用；同 revision 为新 Chunk 写独立向量行并复验，跨 revision 强制重算。
- [x] 4.6 `05-WP04.T3` 在 Query 入口强制使用 Run active document revision 的预处理、模型、维度与距离合同；用 `./mvnw -pl opspilot-adapters/retrieval-infinity,opspilot-adapters/knowledge-pgvector -am test` 验证 revision mismatch 在查询数据库前失败且不换 Provider。

## 5. 05-WP05：Infinity Rerank Adapter

- [x] 5.1 `05-WP05.T1` 在 `opspilot-adapters/retrieval-infinity` 实现独立 Rerank HTTP Adapter，请求携带稳定 `documentId`，统一结果保存 `documentId`、原 index、score、rank 和锁定 provider/model/revision。
- [x] 5.2 `05-WP05.T1` 添加候选往返与排序合同测试，证明 score 不被归一化或跨模型比较，结果可唯一回溯原候选且 Embedding/Rerank 接口未被合并。
- [x] 5.3 `05-WP05.T2` 实现响应 validator，拒绝重复/不存在 index、重复 ID、缺失结果、非有限 score、identity/revision mismatch 和超时部分结果，任何失败不返回原序或 `rerankApplied=true`。
- [x] 5.4 `05-WP05.T2` 运行 Rerank 共享合同、并发双模型隔离、取消和错误映射测试，输出 `outputs/phase5/05-WP05/rerank-contract-report.json`。
- [x] 5.5 `05-WP05.T3` 复用并固化 `scripts/retrieval` 的中文 fixture/benchmark，记录向量基线与 Rerank 的 NDCG@10、MRR、相对提升、延迟、资源、License、镜像 digest 和模型 revision。
- [x] 5.6 `05-WP05.T3` 在锁定开发机执行真实 Infinity 基准，验证 NDCG@10/MRR 均不低于基线且至少一项提升 5%、完整 RAG p95 `< 2s`，将可复算报告写入 `outputs/phase5/05-WP05/`；不达标则阻塞阶段门禁。

## 6. 05-WP06：文档导入、切分与知识版本

- [x] 6.1 `05-WP06.T1` 在 `opspilot-core` 实现 Knowledge ingestion application service、输入格式 allowlist、版本化规范化/Chunk 策略、metadata/ACL 校验和幂等请求合同。
- [x] 6.2 `05-WP06.T1` 集成 `ArtifactPort`，先保存原始文档再创建 document version/Chunk，记录 Artifact、位置、内容哈希和 ACL 引用链；测试格式拒绝、越权、重复导入和相同幂等键不同哈希冲突。
- [x] 6.3 `05-WP06.T2` 扩展 PostgreSQL migration/Repository，创建 immutable knowledge revision、ingestion/embedding job、coverage、失败 Chunk、attempt、checkpoint、文本哈希、模型 revision 和维度所需字段/约束。
- [x] 6.4 `05-WP06.T2` 实现批量向量化编排与可恢复 checkpoint，只有完整批次原子提交，未完成/失败 revision 始终不可 active/searchable；测试进程中断后从正确批次恢复。
- [x] 6.5 `05-WP06.T3` 实现文档更新创建新版本、删除先写 `deleted_at` 并立即过滤，以及受 Artifact/Run/Reference 保留策略保护的异步物理清理。
- [x] 6.6 `05-WP06.T3` 运行 `./mvnw -pl opspilot-core,opspilot-adapters/persistence-postgres,opspilot-adapters/knowledge-pgvector -am test`，覆盖幂等、更新、删除、ACL、批次恢复和历史引用稳定，并输出 `outputs/phase5/05-WP06/ingestion-lifecycle-report.json`。

## 7. 05-WP07：精确召回、强制 Rerank、引用与 Evidence

- [x] 7.1 `05-WP07.T1` 在 `opspilot-core` 定义 Knowledge Search use case/结果合同，在 `opspilot-adapters/knowledge-pgvector` 实现基于 Run 快照的 collection/revision/searchable/deleted/ACL/关系/标签过滤与 allowlist operator 精确 `candidate_k` 查询。
- [x] 7.2 `05-WP07.T1` 添加固定向量数据集集成测试，覆盖 active revision 隔离、Query 维度/revision、关系/标签/ACL/删除过滤、精确排序和 SQL operator 注入在数据库前拒绝。
- [x] 7.3 `05-WP07.T2` 实现 `Embedding → pgvector candidate_k → Rerank → top_k` 编排；候选非空时 Rerank 必经且完整校验成功后才提交，任一技术失败返回 `ChainFailure`。
- [x] 7.4 `05-WP07.T2` 添加负向合同测试，断言 Rerank 超时、鉴权、Schema、index、identity 和取消失败均不产生 vector-only、关键词、固定排序、生成模型排序或替代 Provider 结果。
- [x] 7.5 `05-WP07.T3` 实现 `KnowledgeReference` 持久化/解析与知识 Evidence mapper，保存文档、Chunk、knowledge/model revision、Artifact/位置/哈希、过滤摘要、距离、score/rank 和 Provider identity，并在解析时校验权限与完整性。
- [x] 7.6 `05-WP07.T3` 验证 Knowledge 断言绕过 `EvidenceNormalizer` 无法进入 Diagnosis/Hypothesis/RCA，并生成引用回溯、完整 RAG p95、调用摘要与 secret scan 证据到 `outputs/phase5/05-WP07/`。

## 8. 05-WP08：成功空结果、技术失败与权威状态映射

- [x] 8.1 `05-WP08.T1` 实现 Knowledge outcome mapper：空 collection 返回 `COMPLETED + KB_EMPTY`，非空库成功检索零候选返回 `COMPLETED + NO_MATCH`，两者引用为空且 `rerankApplied=false`。
- [x] 8.2 `05-WP08.T1` 添加调用计数合同测试，证明 `KB_EMPTY` 不调用 Query Embedding/pgvector/Rerank，`NO_MATCH` 完成 Embedding 与 pgvector 但不调用 Rerank，并与“无历史案例”保持可区分。
- [x] 8.3 `05-WP08.T2` 将 Embedding、数据库、权限/引用校验和候选非空后的 Rerank 错误映射为具体 `ChainFailure` 与失败 step/Task，保留 attempt、上游状态、correlation 和 checkpoint。
- [x] 8.4 `05-WP08.T2` 建立空库、零候选及各类技术失败矩阵测试，断言技术失败绝不转换为 `KB_EMPTY`、`NO_MATCH`、MATCH 或替代链路；输出 `outputs/phase5/05-WP08/knowledge-outcome-matrix.json`。
- [x] 8.5 `05-WP08.T3` 更新 `SupervisorPolicy`/状态映射：知识空结果继续现场调查，预算耗尽或 `NO_PROGRESS` 保存 checkpoint 后进入 `GENERATING_REPORT`，只有可回答业务输入缺失进入受 deadline/continuation 上限约束的 `WAITING_INPUT`。
- [x] 8.6 `05-WP08.T3` 运行 `./mvnw -pl opspilot-core,opspilot-a2a -am test` 验证 Run/step/Task 状态矩阵、受限 `PARTIAL/INCONCLUSIVE`、等待恢复和无限重试禁止，输出 `outputs/phase5/05-WP08/state-mapping-report.json`。

## 9. 05-WP09：旁路重向量化、原子切换与回切

- [x] 9.1 `05-WP09.T1` 实现 Re-embedding job 与 quality validator，在新 immutable revision 旁路执行重切分/重向量化并校验文档/Chunk 条数、coverage、ACL、维度、有限值、模型 identity/revision 和固定抽样检索质量。
- [x] 9.2 `05-WP09.T1` 添加批次中断、coverage 缺失、维度漂移、非有限值和质量下降测试，证明新 revision 保持不可搜索且旧 active revision 的固定查询不变。
- [x] 9.3 `05-WP09.T2` 在 `opspilot-adapters/knowledge-pgvector` 实现单事务 revision activation，原子更新 collection active revision 与新旧 `searchable`，并让 Knowledge Search 始终从 Run effective snapshot 解析 revision。
- [x] 9.4 `05-WP09.T2` 执行并发查询与故障注入事务测试，验证切换中不存在双 active/无 active 窗口，旧 Run 固定旧 revision、新 Run 使用新 revision，所有 Query/文档/Rerank identity 不混用。
- [x] 9.5 `05-WP09.T3` 实现经权限与完整性门禁的显式原子回切，以及可重入、可审计、受 Run/KnowledgeReference/Evidence/Artifact 保留策略保护的未激活 revision 清理命令。
- [x] 9.6 `05-WP09.T3` 运行 PostgreSQL/pgvector 集成、回切、清理、真实 Provider、完整 RAG 性能与全仓 Secret scan 门禁，汇总三类共享合同和阶段证据到 `outputs/phase5/05-WP09/phase5-gate-summary.json`；任一真实门禁缺失或失败时不得标记阶段完成。
