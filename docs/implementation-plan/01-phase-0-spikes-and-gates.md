# 阶段 01：Phase 0——合同、依赖与真实能力门禁

## 目标

在完整功能开发前消除关键技术不确定性，锁定依赖和镜像，验证 AgentScope、A2A、真实模型、机器合同、最小数据库边界与一条真实纵切。这个阶段只允许骨架和 Spike；其输出必须是可自动验证的最小实现，不能以临时代码冒充后续完整模块。

## 前置条件

- 以 `docs/design/contracts/` 的 OpenAPI 与 JSON Schema 为最高优先级机器事实源；Profile 和示例是必须通过对应 Schema 校验的版本化实例，不得覆盖第 23.1 节中优先级更高的状态矩阵、不变量或正文边界。
- 对设计第 22.3 节和各章“待确认”项建立清单；本阶段只闭环阻止编码的版本、模型和框架 API 项。

## 建设范围

1. 建立 JDK 21/Maven 多模块最小骨架，模块名直接采用第 28.3 节，不拆出额外通用 Extension Host。
2. 创建 `deployment/versions.lock.yaml`，锁定 JDK 21、Maven、Spring Boot、AgentScope Java、A2A Java SDK、PostgreSQL/pgvector、Infinity 和其他验收基础镜像 digest，以及 Embedding/Rerank 的不可变模型 revision。禁止 `latest`、浮动 minor、占位符和只有 tag 没有 digest 的基础设施镜像。
3. 使用 OpenAPI 3.1 validator 和 JSON Schema Draft 2020-12 validator，为 OpenAPI、全部 JSON Schema、Profile 和现有示例建立 lint/编译/实例校验；加入错误版本、缺少 required、非法枚举和未知安全敏感字段等故意失败样例，证明错误合同会阻止合并。`schemaVersion` 只表示 payload 合同版本，A2A `protocolVersion` 只表示传输协议版本，两者不得混用。
4. 完成 AgentScope Spike：项目 Chat/Tool Adapter、结构化输出与 Tool calling、一次结构修复、Middleware 审计、PostgreSQL `AgentStateStore`、重启恢复、最大轮次、deadline、取消、重复动作和 `NO_PROGRESS`；中止后不得继续调用 Tool/Model，状态保存失败不得退化到内存或本地文件。同时通过真实 Provider 对默认及六角色所引用的逻辑 LLM Profile 验证具体模型身份、Secret ref、上下文上限、Tool/结构化/所需流式能力和配额，输出去密钥的版本化验收记录。
5. 完成 A2A 1.0 Spike，并把官方 release/SDK 或官方 proto 生成对象锁定到 `v1.0.1`：六张 Card 的 `protocolVersion` 固定为 `1.0`，验证 `send/stream/get/cancel/subscribe`、持久 Task Store、stream 恢复、messageId 幂等、Artifact 媒体类型/Schema/哈希/身份校验和数据库权限隔离；重复 messageId 的相同请求返回原 Task，不同请求哈希必须冲突。
6. 完成 `retrieval-model-probe`：同一 Infinity 实例加载真实 Embedding 与 Rerank；校验 `/models`、三次 Embedding、实际维度、归一化与有限值、正负样本排序、并发不串模型，并输出带镜像 digest、不可变 revision、探针版本和 UTC 时间的 JSON。同步完成目标开发机上的模型选型门禁，记录模型/权重来源、License、CPU/内存/GPU、冷/热启动、最大长度、并发干扰和中文效果；NDCG@10 与 MRR 均不得低于仅向量基线且至少一项提升 ≥ 5%，最小真实 Embedding+召回+Rerank 链 p95 < 2 秒。
7. 建立最小 Flyway 和角色测试：四个 schema、pgvector 扩展、空库/前一版本升级、专业 Agent 权限、单 Incident 单活动 Run 并发约束。
8. 建立 Prometheus、Jaeger、JSONL、Actuator、Compose Adapter 的最小合同实现，证明 ObservationBatch 单 Source、Resource/Source 归属、Artifact 哈希和 Evidence provenance 可追溯。
9. 按冻结拓扑创建一个 Supervisor/产品进程和五个专业 Agent 进程的 Compose 骨架；专业 Agent 仅内网暴露，各自使用 `AGENT_ID`、Token 和数据库角色。
10. 打通最小 `Incident → Evidence → RCA → Evaluation` 纵切。RCA 允许 `INCONCLUSIVE/rootCause=null`，但所有 Provider 必须真实可调用，且不得访问 Ground Truth。
11. 先落地 `.github/workflows/contracts.yml`；Maven 骨架存在后同时建立 `build-test.yml` 和 `security.yml` 的最小必需检查。输出符合 Schema 的阶段证据/Release Manifest，且任何 Workflow 都不部署环境。

## 详细实施计划

### 01-WP01：仓库骨架与不可变版本清单

**输入：**第 24.1、24.3、24.4、28.3 节；当前仓库结构。

**代码/配置落点：**根 `pom.xml`、`.mvn/`、`mvnw*`、第 28.3 节模块目录、`deployment/versions.lock.yaml`、基础 Dockerfile/Compose 文件。

**任务：**

1. `01-WP01.T01` 创建根 Maven 聚合工程和 Wrapper，设置 JDK release 21、UTF-8、统一测试插件版本及禁止未声明插件版本的构建规则。
2. `01-WP01.T02` 只创建第 28.3 节物理模块；为每个模块放置最小 `pom.xml` 和包级说明，暂不添加业务实现或额外 Extension Host。
3. `01-WP01.T03` 建立依赖方向的第一版架构测试，使 core 无法导入 Spring、AgentScope、A2A SDK、JPA 和厂商包。
4. `01-WP01.T04` 填写 `versions.lock.yaml` 的精确依赖版本、基础镜像 digest、模型 ID/revision；为必填字段增加 Schema 或校验脚本，拒绝空值、`latest`、浮动 minor 和无 digest 镜像。
5. `01-WP01.T05` 从空 Maven/容器缓存执行解析，保存依赖树、License、SBOM 和镜像摘要证据。

**验证：**`./mvnw -B -ntp verify` 可从空缓存完成；版本清单负向样例分别因占位符、浮动版本和缺 digest 失败。

### 01-WP02：机器合同校验链

**输入：**`docs/design/contracts/` 的 OpenAPI、全部 JSON Schema、Profile 和示例。

**代码/配置落点：**`scripts/contracts/` 或等价构建入口、合同测试源码、`.github/workflows/contracts.yml`。

**任务：**

1. `01-WP02.T01` 固定 OpenAPI 3.1 和 JSON Schema Draft 2020-12 validator 版本，编译每个 Schema 并校验 `$ref` 可解析。
2. `01-WP02.T02` 建立 Schema 到示例/Profile 的显式映射，不能只扫描“看起来像 JSON/YAML”的文件；确保场景、Ground Truth、Agent Profile、观测、Evidence、RCA、Evaluation 和 Release Manifest 均被覆盖。
3. `01-WP02.T03` 增加错误版本、缺 required、非法枚举、错误 UUID/哈希、未知安全字段和 `schemaVersion/protocolVersion` 混用的负向 fixture。
4. `01-WP02.T04` 校验 OpenAPI operationId、响应 Schema 和引用完整性，检查阶段文档与设计文档的本地 Markdown 链接。
5. `01-WP02.T05` 在 `contracts.yml` 中运行同一脚本，并上传包含 validator 版本、文件数、成功/失败数和哈希的报告。

**验证：**所有正式合同/示例通过；每个负向 fixture 单独启用时使本地命令和 Workflow 非零退出。

### 01-WP03：AgentScope 可行性 Spike

**输入：**第 24.5 节断言、锁定候选 AgentScope 版本。

**代码/配置落点：**`opspilot-agent-runtime-agentscope` 的最小 Adapter、测试源码及 PostgreSQL 测试 migration；Spike 代码必须位于未来正式模块边界内。

**任务：**

1. `01-WP03.T01` 以项目自己的 Chat/Tool Port 适配框架类型，证明 AgentScope 类型不进入 core。
2. `01-WP03.T02` 实现最小结构化 Decision/Result Schema、Tool calling 和最多一次结构修复；保存决策摘要而非隐藏推理。
3. `01-WP03.T03` 通过 Middleware/事件采集 round、动作指纹、Usage、checkpoint 和取消信号。
4. `01-WP03.T04` 实现 PostgreSQL `AgentStateStore`，按 `(serverAgentId,userId,sessionId)` 隔离并验证进程重启恢复。
5. `01-WP03.T05` 分别触发 max rounds、deadline、外部取消、重复动作和连续无新 Evidence，断言循环以预期 reasonCode 停止。
6. `01-WP03.T06` 注入状态保存失败，断言不再调用 Tool/Model，且不创建内存或文件回退状态。

**验证：**第 24.5 节每条断言具有独立自动化测试；测试报告标出使用的 Maven 坐标、License 和实际 API。

### 01-WP04：真实 Chat 模型与六角色能力门禁

**输入：**默认及六角色逻辑模型 Profile、AgentProfile 所需能力、目标测试 Provider 凭证。

**代码/配置落点：**Phase 0 探针入口、去密钥验收记录、临时测试配置；正式 Provider 业务能力仍在阶段 05 完成。

**任务：**

1. `01-WP04.T01` 解析默认配置和六角色稀疏覆盖，列出最终模型 identity、上下文上限、结构化输出、Tool calling、流式要求和配额。
2. `01-WP04.T02` 对去重后的每个实际模型执行真实最小调用；需要的能力必须分别探测，不能从 OpenAI-Compatible 名称推断。
3. `01-WP04.T03` 记录模型、Provider、配置版本、探针请求类型、UTC 时间、Usage/配额和脱敏错误，不保存 Key、完整 Prompt 或完整响应。
4. `01-WP04.T04` 为模型为空、Key 缺失、上下文上限未知、能力不支持和配额不足建立 fail-fast 测试。

**验证：**默认及六角色所引用的每个逻辑 Profile 均有真实能力记录；任何未验证 Profile 都阻止阶段完成。

### 01-WP05：A2A 1.0.1 互操作 Spike

**输入：**第 5、6、23.4、24.6 节以及锁定官方 SDK/生成对象。

**代码/配置落点：**`opspilot-a2a` 的 contract/client/server 包、最小持久 Task Store、测试 Card 和抓包证据。

**任务：**

1. `01-WP05.T01` 生成六张最小 Card，固定 `protocolVersion: "1.0"`、HTTP+JSON、skill 和媒体类型，并用锁定对象合同校验。
2. `01-WP05.T02` 实现并测试 `send/stream/get/cancel/subscribe`，确保 stream 状态和 Artifact 可从持久 Store 重取。
3. `01-WP05.T03` 实现 messageId + request hash 幂等；相同请求返回原 Task，不同 hash 返回冲突。
4. `01-WP05.T04` 校验 Artifact 媒体类型、major Schema、SHA-256、Task/调用方身份和访问权限；失败不得写入领域表。
5. `01-WP05.T05` 重启 Server/Client、断开 stream、重复发送和取消竞争，验证 Task 终态不可离开且不会重复副作用。
6. `01-WP05.T06` 使用抓包或代理日志证明调用经过真实 HTTP+JSON；禁用任何 Spring Bean 直调路径。

**验证：**第 24.6 节断言全部自动化；专业 Agent 数据库角色写 `opspilot` 领域表的测试必须失败。

### 01-WP06：Embedding/Rerank 选型与检索探针

**输入：**Infinity 候选镜像、Embedding/Rerank 候选模型、首期中文故障知识集和固定开发机规格。

**代码/配置落点：**`retrieval-model-probe` 程序、`deployment/versions.lock.yaml`、版本化选型结果 JSON 和基准报告。

**任务：**

1. `01-WP06.T01` 在一个 Infinity 实例中加载两个准确 revision，检查 `/models` 的 served name、模型 ID 和加载能力。
2. `01-WP06.T02` 对固定文本执行至少三次 Embedding，检查数量、顺序、维度、有限值、归一化约定和 COSINE 非零范数。
3. `01-WP06.T03` 对固定正负文档执行 Rerank，检查原 index 完整、分数有限、正样本排序及响应 identity。
4. `01-WP06.T04` 并发执行 Embedding/Rerank，检查不串模型并记录 CPU、内存/GPU、冷/热启动、最大长度和吞吐干扰。
5. `01-WP06.T05` 使用固定查询集比较向量基线与 Rerank 后的 NDCG@10/MRR；记录中文质量、License、权重来源和供应链身份。
6. `01-WP06.T06` 测量最小真实 `Embedding → 精确召回 → Rerank` p50/p95，验证 p95 `< 2s`。
7. `01-WP06.T07` 输出通过 Schema 的探针 JSON；任一断言失败时一次性容器非零退出。

**验证：**NDCG@10、MRR、提升比例和 p95 达到冻结门禁；结果包含镜像 digest、不可变 revision、机器规格、探针版本和 UTC 时间。

### 01-WP07：最小 PostgreSQL/Flyway 与角色边界

**输入：**第 9、10、23.6、24.8 节。

**代码/配置落点：**`opspilot-adapters/persistence-postgres`、最小 Flyway、PostgreSQL 初始化角色脚本和 Testcontainers 测试。

**任务：**

1. `01-WP07.T01` 创建 pgvector 扩展和四个 schema，区分 migrator、app、Sample、Fault Lab、Evaluation 和专业 Agent 角色。
2. `01-WP07.T02` 只实现 Spike 所需的 Incident/Run、最小 Agent state/A2A state、Artifact/Evidence/Evaluation 记录，不提前补齐全部业务表。
3. `01-WP07.T03` 建立单 Incident 单活动 Run 的数据库唯一约束及冲突映射测试。
4. `01-WP07.T04` 执行空库和生产前一版本升级测试；验证专业 Agent 不能写 Supervisor 领域表、Agent 无法访问 Ground Truth。

**验证：**迁移、回滚、角色、并发约束均由锁定 PostgreSQL/pgvector Testcontainers 通过，不使用 H2。

### 01-WP08：最小 Source/Observation/Evidence 合同链

**输入：**第 27 章和 ObservationBatch/EvidenceBundle Schema。

**代码/配置落点：**`opspilot-adapters/observability`、最小 Source Registry、EvidenceNormalizer 路径、真实响应 fixture/Artifact。

**任务：**

1. `01-WP08.T01` 为 Prometheus、Jaeger、JSONL、Actuator 和 Compose 建立最小真实 Adapter 与稳定 descriptor。
2. `01-WP08.T02` 强制一次调用只生成一个 Source 的 ObservationBatch，并保留 Resource、query hash、Artifact 和 provenance。
3. `01-WP08.T03` 建立共享合同测试：成功、合法空结果、超时、鉴权失败、Schema 无效、取消、脱敏和哈希错误。
4. `01-WP08.T04` 规范化最小 Observation 为不可变 Evidence，验证 Agent/RCA/Evaluation 不接收厂商 DTO。

**验证：**五类 Adapter 通过共享套件；每条 Spike Evidence 可反查 Batch、Record、Source 和 Artifact。

### 01-WP09：冻结的六进程 Compose 骨架

**输入：**第 24.1–24.3 节。

**代码/配置落点：**`deployment/docker-compose.yml`、Agent 入口、`deployment/agents/agent-directory.yaml`、健康检查。

**任务：**

1. `01-WP09.T01` 创建 8080 产品/Supervisor 和 8081–8085 五专业 Agent 服务；专业端口不绑定宿主机。
2. `01-WP09.T02` 为每个进程注入不同 `AGENT_ID`、服务 Token、数据库角色和 A2A URL；Token 仅允许对应 skill。
3. `01-WP09.T03` 挂载 Agent input 只读卷，不挂载 Ground Truth/execution 敏感卷或 Docker socket。
4. `01-WP09.T04` 将 Migration 作为硬启动依赖；一次性 retrieval probe 作为 Phase 0/部署资格门禁，不作为 Server/Agent 的完成型启动依赖。Server/Agent 自身执行等价探针：确定性不兼容时非零退出，配置合法但端点暂时不可达时保持 liveness UP、readiness DOWN；Card/endpoint 临时不可达同样只阻止 readiness。

**验证：**`docker compose config` 通过；六个 endpoint ready；抓包确认专业调用只走 Compose 网络 HTTP。

### 01-WP10：最小真实纵切与阶段证据

**输入：**前述工作包全部通过。

**代码/配置落点：**最小 API/Use Case、一个真实 Source、真实模型、RCA/Evaluation Artifact、调用审计和阶段 Release Manifest。

**任务：**

1. `01-WP10.T01` 创建最小 Incident/Run，并经一个专业 Agent 获取真实 Observation/Evidence。
2. `01-WP10.T02` 使用真实 LLM 生成受 Evidence 约束的结构化 RCA；允许 `INCONCLUSIVE/rootCause=null`。
3. `01-WP10.T03` 运行独立确定性 Evaluation，保存 Incident、Evidence、RCA、Evaluation 与关联调用审计。
4. `01-WP10.T04` 注入模型、Source、A2A 和数据库故障，断言无 Mock/Fake、无固定结果、无静默切换。
5. `01-WP10.T05` 建立 `build-test.yml`、`security.yml` 最小检查，并生成符合 Schema、`automaticDeployment=false` 的阶段 Manifest。

**验证：**从干净环境用单一命令链完成纵切；所有 Artifact/报告具有 URI、SHA-256、commit 和配置身份。

## 阶段内执行顺序

```text
01-WP01
├─→ 01-WP02
├─→ 01-WP03 ─→ 01-WP04 ─┐
├─→ 01-WP05 ─────────────┤
├─→ 01-WP06 ─────────────┼─→ 01-WP09
└─→ 01-WP07 ─→ 01-WP08 ─┘
全部通过 ─→ 01-WP10
```

## 测试与证据矩阵

| 验证层 | 必测内容 | 失败条件 | 证据 |
|---|---|---|---|
| 静态合同 | OpenAPI、Schema、Profile、示例、版本锁 | 任一未校验、负向 fixture 未失败 | 合同报告、validator/lock digest |
| 框架 Spike | AgentScope/A2A 全部冻结断言 | 需修改 core 语义、存在内存直调/状态回退 | 测试报告、抓包、依赖树 |
| 模型 Spike | Chat/Embedding/Rerank 真实能力 | 空模型、能力未知、质量/延迟不达标 | 能力快照、选型/基准报告 |
| 数据与权限 | Flyway、pgvector、角色、单活动 Run | H2 替代、越权成功、并发双提交 | Testcontainers 报告、SQL 证据 |
| 纵切 | Incident → Evidence → RCA → Evaluation | Mock/Fake、Ground Truth 泄漏、Artifact 不可追溯 | 全链 Artifact、审计、Manifest |

## 主要输出

- 根 `pom.xml`、Maven Wrapper 和第 28.3 节规定的模块目录骨架；
- `deployment/versions.lock.yaml`、`deployment/docker-compose.yml`、`deployment/agents/agent-directory.yaml`；
- AgentScope/A2A Spike 代码与自动化测试；
- `retrieval-model-probe` 小程序及版本化 JSON 结果；
- 默认/六角色 LLM 能力与配额验收记录，以及 Embedding/Rerank 选型、License、资源、中文质量和延迟记录；
- 最小 Flyway、数据库角色和 Testcontainers 测试；
- 合同校验脚本/测试，以及 `contracts.yml`、`build-test.yml`、`security.yml`；
- 最小纵切的 Incident、Evidence、RCA、Evaluation Artifact 和调用审计。

## 完成门禁

- `versions.lock.yaml` 无占位符，依赖可从空缓存解析并通过 License/SBOM 检查。
- AgentScope 和 A2A 的第 24.5、24.6 节断言全部自动化通过；抓包确认无进程内 Agent 快捷调用。
- 第 22.3 节待确认项 1–4 已由实际构建、真实 Provider/模型调用和目标开发机结果闭环；空模型、未验证上下文/能力/配额、未验证维度/归一化/License/资源、未验证 Rerank 或未验证框架 API 均不能通过。
- OpenAPI 3.1、所有 Draft 2020-12 Schema、Profile 和示例通过；失败样例能使检查失败，Java/Python DTO、Agent Artifact 校验和 Evaluation 读取不复制另一套字段定义。
- 五类首期 Source Adapter 通过共享合同测试，Observation/Evidence 来源可追溯。
- Flyway 空库/升级、角色权限和单活动 Run 并发测试通过。
- 六个 Agent endpoint 在 Compose 中 ready；模型身份、revision、维度、协议或 required capability 等确定性校验失败时 Server/Agent 非零退出，配置合法但真实模型端点暂时不可达时进程保持 liveness UP、readiness DOWN，不接收新任务并在端点恢复后重新探针。
- 三个场景合同和评测公式保持冻结；最小真实纵切完成且没有 Mock/Fake/替代链路。
- Workflow 只产生校验证据和 Manifest，不保存目标环境凭证、不执行部署。

## 明确不做

- 不在本阶段补齐全部业务表、九个 Tool、完整六 Agent 行为或三场景 E2E。
- 不因 Spike 方便而改变 core Port、状态所有权、A2A 媒体类型或数据库 schema 边界；确需改变必须先 ADR 和设计复审。

## 设计依据

- [实现合同与 Definition of Ready](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
- [运行拓扑与 Phase 0 门禁](../design/opspilot-system-design/13-runtime-topology-and-phase0-gates.md)
- [分布式目标与 Source Adapter](../design/opspilot-system-design/16-distributed-target-and-observability-adapters.md)
- [内聚核心与模块边界](../design/opspilot-system-design/17-cohesive-core-and-controlled-extensions.md)
