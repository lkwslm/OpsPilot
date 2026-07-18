## 1. 01-WP01：仓库骨架与不可变版本清单

- [x] 1.1 **01-WP01.T01** 创建根 `pom.xml` 与 Maven Wrapper，锁定 JDK release 21、UTF-8、测试插件版本及“插件版本必须声明”规则；从空 Maven 缓存执行 `./mvnw -B -ntp verify` 并保存命令、退出码和日志哈希。
- [x] 1.2 **01-WP01.T02** 仅创建第 28.3 节冻结的 `opspilot-core`、`opspilot-tools-default`、`opspilot-agent-runtime-agentscope`、`opspilot-a2a`、`opspilot-adapters/*`、`opspilot-server`、`opspilot-evaluation`、`sample-system`、`fault-lab` 和 `deployment` 物理边界，为每个 Maven 模块添加最小 `pom.xml` 与包级说明；用 reactor 清单证明没有额外 Extension Host 或业务实现。
- [x] 1.3 **01-WP01.T03** 在 Maven 骨架中加入第一版架构测试，禁止 `opspilot-core` 导入 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 或厂商包，并添加一个可证明规则会失败的测试样例。
- [x] 1.4 **01-WP01.T04** 创建 `deployment/versions.lock.yaml` 及其 Schema/校验脚本，记录精确依赖版本、基础镜像 digest、模型 ID/revision；分别验证空值、占位符、`latest`、浮动 minor 和无 digest 镜像均非零退出，并保存清单哈希。
- [x] 1.5 **01-WP01.T05** 从空 Maven/容器缓存解析全部锁定依赖和镜像，生成依赖树、License、SBOM 与镜像摘要证据；报告必须关联 commit、版本锁哈希和工具版本。无法识别的 License 记录为 `WARNING` 且不阻塞应用跑通，依赖版本、模型 revision 或镜像 digest 等身份缺口仍使工作包保持 `BLOCKED`。

## 2. 01-WP02：机器合同校验链

- [x] 2.1 **01-WP02.T01** 锁定 OpenAPI 3.1 validator 与 JSON Schema Draft 2020-12 validator，在 `scripts/contracts/` 或等价构建入口编译全部 Schema 并解析全部 `$ref`；记录 validator 版本、输入文件数和哈希。
- [x] 2.2 **01-WP02.T02** 建立 Schema 到 Profile/示例的显式映射，覆盖场景、Ground Truth、Agent Profile、Observation、Evidence、RCA、Evaluation 和 Release Manifest；加入覆盖率断言，任何未映射正式实例使校验失败。
- [x] 2.3 **01-WP02.T03** 为错误版本、缺 required、非法枚举、错误 UUID/哈希、未知安全敏感字段和 `schemaVersion/protocolVersion` 混用建立独立负向 fixture；逐个启用并证明同一本地入口以预期原因非零退出。
- [x] 2.4 **01-WP02.T04** 校验 OpenAPI operationId 唯一性、响应 Schema 和引用完整性，并扫描阶段/设计文档的本地 Markdown 链接；为断开 `$ref` 和断开链接各保留一个会失败的自动化测试。
- [x] 2.5 **01-WP02.T05** 创建 `.github/workflows/contracts.yml`，运行与本地完全相同的合同入口并上传包含 validator 版本、文件数、成功/失败数和输入哈希的报告；确认 Workflow 无目标环境凭证和部署步骤。

## 3. 01-WP03：AgentScope 可行性 Spike

- [x] 3.1 **01-WP03.T01** 在 `opspilot-agent-runtime-agentscope` 内用项目自有 Chat/Tool Port 适配锁定候选 AgentScope Java API，添加架构测试证明框架类型不进入 core，并在证据中记录实际 Maven 坐标、API 和 License；不兼容时标记 `BLOCKED` 而不修改 core 语义。
- [x] 3.2 **01-WP03.T02** 实现最小 Decision/Result Schema、真实 Tool calling 和最多一次结构修复，只持久化决策摘要；自动化测试覆盖首轮合法、一次修复成功及修复后仍非法三条路径。
- [x] 3.3 **01-WP03.T03** 通过 Middleware/事件采集 round、动作指纹、Usage、checkpoint 和取消信号，验证审计顺序、字段脱敏及不保存隐藏推理。
- [x] 3.4 **01-WP03.T04** 实现 PostgreSQL `AgentStateStore`，按 `(serverAgentId,userId,sessionId)` 隔离；用 Testcontainers 保存两个会话、重启进程并验证各自 checkpoint 恢复且不会串状态。
- [x] 3.5 **01-WP03.T05** 分别触发 max rounds、deadline、外部取消、重复动作和连续无新 Evidence，断言每条路径以冻结 reasonCode 停止，并证明停止事件后 Tool/Model 调用数不再增加。
- [x] 3.6 **01-WP03.T06** 注入 `AgentStateStore` 保存失败，断言运行立即 fail-closed、无后续 Tool/Model 调用、无内存/本地文件回退；汇总第 24.5 节断言到独立测试与版本化报告。

## 4. 01-WP04：真实 Chat 模型与六角色能力门禁

- [x] 4.1 **01-WP04.T01** 实现 Phase 0 Profile 解析入口，展开默认配置和六角色稀疏覆盖，输出每个逻辑 Profile 的最终模型 identity、Secret ref、上下文上限、结构化输出、Tool calling、流式要求和配额；测试覆盖覆盖优先级及 Secret 不出现在输出中。
- [x] 4.2 **01-WP04.T02** 对去重后的每个实际模型执行真实 Provider 最小调用，并分别探测每项必需能力；禁止依据 OpenAI-Compatible 名称或静态声明推断，缺少凭证/配额时记录外部条件并保持 `BLOCKED`。
- [x] 4.3 **01-WP04.T03** 生成去密钥版本化验收记录，包含模型、Provider、配置版本、请求类型、UTC 时间、Usage/配额和脱敏错误；运行泄漏检查证明没有 Key、完整 Prompt、完整响应或 Ground Truth。
- [x] 4.4 **01-WP04.T04** 为模型为空、Key 缺失、上下文上限未知、必需能力不支持和配额不足添加 fail-fast 测试；汇总门禁必须逐一回填默认及六角色逻辑 Profile，任何未验证 Profile 都非零退出。

## 5. 01-WP05：A2A 1.0.1 互操作 Spike

- [x] 5.1 **01-WP05.T01** 将官方 A2A release/SDK 或官方 proto 生成对象锁定到 `v1.0.1`，生成六张最小 Card，固定 `protocolVersion: "1.0"`、HTTP+JSON、skill 和媒体类型，并以锁定对象合同自动校验。
- [x] 5.2 **01-WP05.T02** 在 `opspilot-a2a` 的 contract/client/server 包实现并测试 `send/stream/get/cancel/subscribe` 与最小 PostgreSQL Task Store；验证 stream 状态和 Artifact 在断流及 Server/Client 重启后可重取。
- [x] 5.3 **01-WP05.T03** 实现 `messageId + request hash` 幂等：相同请求返回原 Task 且无重复副作用，不同 hash 返回明确冲突；覆盖并发重复发送。
- [x] 5.4 **01-WP05.T04** 在领域写入前校验 Artifact 媒体类型、major Schema、SHA-256、Task/调用方身份和访问权限；为每种失败添加负向测试，并证明专业 Agent 数据库角色无法写 `opspilot` 领域表。
- [x] 5.5 **01-WP05.T05** 测试 Server/Client 重启、stream 断开、重复发送和取消竞争，断言 Task 终态不可离开、状态可恢复且不会重复副作用。
- [x] 5.6 **01-WP05.T06** 在六进程或最小跨进程环境收集抓包/代理日志，证明调用经过真实 HTTP+JSON；禁用网络后调用必须失败，以架构测试和运行测试共同排除 Spring Bean 直调，并汇总第 24.6 节证据。

## 6. 01-WP06：Embedding/Rerank 选型与检索探针

- [x] 6.1 **01-WP06.T01** 在一个锁定 digest 的 Infinity 实例加载两个准确 revision，查询 `/models` 并校验 served name、模型 ID 和加载能力与 `versions.lock.yaml` 一致。
- [x] 6.2 **01-WP06.T02** 对固定文本至少执行三次真实 Embedding，校验数量、顺序、实际维度、有限值、归一化约定和 COSINE 非零范数；把输入集与结果摘要哈希写入报告。
- [x] 6.3 **01-WP06.T03** 对固定正负文档执行真实 Rerank，校验原 index 完整、分数有限、正样本排序及响应 identity；错误 identity 或排序使探针非零退出。
- [x] 6.4 **01-WP06.T04** 并发运行 Embedding/Rerank，证明不串模型，并记录目标开发机 CPU、内存/GPU、冷/热启动、最大长度、吞吐和并发干扰。
- [x] 6.5 **01-WP06.T05** 用固定中文查询集比较精确向量基线与 Rerank 后 NDCG@10/MRR，记录模型/权重来源、License 和中文质量；两项指标均不得低于基线且至少一项相对提升不低于 5%。
- [x] 6.6 **01-WP06.T06** 测量最小真实 `Embedding → 精确召回 → Rerank` 的 p50/p95，固定开发机上 p95 必须 `< 2s`；保存原始测量、聚合算法、机器规格和输入哈希。
- [x] 6.7 **01-WP06.T07** 定义并校验版本化探针结果 JSON，包含镜像 digest、不可变 revision、机器规格、资源/质量/延迟、探针版本和 UTC 时间；让一次性容器在任一断言失败时非零退出，并将未达标模型选型保持 `BLOCKED`。

## 7. 01-WP07：最小 PostgreSQL/Flyway 与角色边界

- [x] 7.1 **01-WP07.T01** 在 `opspilot-adapters/persistence-postgres` 创建最小 Flyway 和角色初始化脚本，启用 pgvector、建立四个冻结 schema，并区分 migrator、app、Sample、Fault Lab、Evaluation 和专业 Agent 角色；全部测试使用锁定 PostgreSQL/pgvector Testcontainers，不得使用 H2。
- [x] 7.2 **01-WP07.T02** 只实现 Spike 所需 Incident/Run、最小 Agent state/A2A state、Artifact/Evidence/Evaluation 记录；用 migration 内容审查和集成测试证明纵切所需数据可持久化且未提前补齐完整业务表。
- [x] 7.3 **01-WP07.T03** 建立单 Incident 单活动 Run 的数据库唯一约束及冲突映射，并用两个并发事务证明仅一个活动 Run 提交成功。
- [x] 7.4 **01-WP07.T04** 执行空库和生产前一版本升级测试，验证专业 Agent 不能写 Supervisor 领域表且 Agent 无法访问 Ground Truth；保存 migration、角色、并发和拒绝访问证据。

## 8. 01-WP08：最小 Source/Observation/Evidence 合同链

- [x] 8.1 **01-WP08.T01** 在 `opspilot-adapters/observability` 为 Prometheus、Jaeger、JSONL、Actuator 和 Compose 实现最小真实 Adapter 与稳定 descriptor，并为每类 Source 保存可重放的真实响应 fixture/Artifact。
- [x] 8.2 **01-WP08.T02** 强制一次 Adapter 调用只生成一个 Source 的 ObservationBatch，保留 Resource、Source、query hash、原始 Artifact、内容哈希和 provenance；自动化测试从 Evidence 反查 Batch、Record、Source 与 Artifact。
- [x] 8.3 **01-WP08.T03** 建立五类 Adapter 共享合同套件，覆盖成功、合法空结果、超时、鉴权失败、Schema 无效、取消、脱敏和哈希错误；禁止静默切换 Source 或伪造结果。
- [x] 8.4 **01-WP08.T04** 通过 EvidenceNormalizer 把最小 Observation 转换为不可变 Evidence，添加架构/合同测试拒绝 Agent、RCA、Evaluation 直接接收厂商 DTO，并生成每条 Spike Evidence 的 provenance 证据。

## 9. 01-WP09：冻结的六进程 Compose 骨架

- [x] 9.1 **01-WP09.T01** 创建 `deployment/docker-compose.yml` 与健康检查，配置 8080 产品/Supervisor 和 8081–8085 五专业 Agent；执行 `docker compose config`，验证只绑定产品端口到宿主机且六个 endpoint 最终 ready。
- [x] 9.2 **01-WP09.T02** 为每个进程注入不同 `AGENT_ID`、服务 Token ref、数据库角色和 A2A URL，在 `deployment/agents/agent-directory.yaml` 声明对应 skill；测试跨 Agent Token/角色访问被拒绝。
- [x] 9.3 **01-WP09.T03** 将 Agent input 以只读卷挂载，移除 Ground Truth、execution 敏感卷和 Docker socket；通过 Compose 配置检查与容器内访问测试证明专业 Agent 不可见这些资产。
- [x] 9.4 **01-WP09.T04** 将 Migration 设为硬启动依赖、一次性 retrieval probe 设为 Phase 0/部署资格门禁而非完成型启动依赖；实现 Server/Agent 等价探针并分别测试确定性不兼容时非零退出、临时模型/Card/endpoint 不可达时 liveness UP/readiness DOWN、恢复后重新探针；抓包确认专业调用只走 Compose HTTP 网络。

## 10. 01-WP10：最小真实纵切与阶段证据

- [x] 10.1 **01-WP10.T01** 创建最小 Incident/Run Use Case，经一个专业 Agent 和真实 A2A HTTP+JSON 调用一个真实 Source，持久化 Observation、Artifact 与 Evidence；记录 Source、Task、Trace、配置和 commit 身份。
- [x] 10.2 **01-WP10.T02** 使用已通过能力门禁的真实 LLM 生成受 Evidence 约束的结构化 RCA，允许 `INCONCLUSIVE/rootCause=null`；验证引用只指向允许的 Evidence，Agent/RCA 无 Ground Truth 访问。
- [x] 10.3 **01-WP10.T03** 运行独立确定性 Evaluation，持久化 Incident、Evidence、RCA、Evaluation 关联与调用审计；从 Evaluation 反查所有 Artifact URI、SHA-256、commit 和配置身份并验证哈希。
- [x] 10.4 **01-WP10.T04** 分别注入模型、Source、A2A 和数据库故障，证明纵切无 Mock/Fake、固定结果或静默 Provider/Source/存储切换；每种故障产生冻结失败语义或允许的 `INCONCLUSIVE` 及脱敏证据。
- [x] 10.5 **01-WP10.T05** 创建 `.github/workflows/build-test.yml` 和 `security.yml` 最小检查，与 `contracts.yml` 汇总所有 Phase 0 门禁和证据，生成符合 Schema、`automaticDeployment=false` 的阶段 Release Manifest；从干净环境执行单一命令链，确认任何失败/缺证据/`BLOCKED` 项都会阻止阶段完成，且 Workflow 不持有目标环境凭证、不执行部署。
