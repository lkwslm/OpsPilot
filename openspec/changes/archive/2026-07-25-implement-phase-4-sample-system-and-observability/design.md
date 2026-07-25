## 背景

阶段 03 已建立 PostgreSQL/pgvector、Resource/Topology/Source/Observation/Evidence/Artifact 表、Repository/UnitOfWork 和 Artifact 访问边界，但现有系统尚不能从真实业务请求产生可供后续诊断使用的可观测事实。阶段 04 需要同时跨越 Sample 应用、故障测试面、遥测后端、Source Adapter、Evidence 规范化、Tool 和 Compose/CI，因此必须先冻结所有权、数据流和失败语义，再实施纵切。

设计事实源按以下顺序生效：`docs/design/contracts/` 的 OpenAPI 与 JSON Schema，设计中的状态/能力矩阵和不变量，`docs/implementation-plan/04-sample-system-and-observability.md`，最后才是本变更。若实现迫使上述合同或阶段边界变化，对应工作包保持阻塞并先提交 ADR/设计修订。

阶段内依赖固定为：`04-WP01 → 04-WP02/03`，`阶段 03 + 04-WP01 → 04-WP04 → 04-WP05 → 04-WP06 → 04-WP07 → 04-WP08 → 04-WP09`。`04-WP06` 与 `04-WP07` 不得作为无依赖工作并行关闭，`04-WP08` 只能消费已经稳定的 Port/Normalizer 合同。

## 目标与非目标

**目标：**

- 让 `sample-gateway`、`order-service`、`inventory-service` 通过真实 PostgreSQL、HikariCP 和乐观锁产生确定且可观测的业务流量。
- 提供仅测试环境可见、可显式恢复的故障入口，同时把代理和容器生命周期控制留给 Fault Lab。
- 用稳定 Resource/Source 身份和版本化拓扑，把 Prometheus、长期并行兼容的 Jaeger v1/v2、JSONL、Actuator、HTTP、配置和 Compose 响应转换成单 Source `ObservationBatch`。
- 在进入分析层前完成 Schema、权限、归属、时间、质量、脱敏、限长、Artifact 完整性和去重校验，生成不可变 Evidence/provenance。
- 以六个能力型 Tool 和真实 Compose smoke 证明厂商 DTO、任意查询语言及 Docker 控制权没有越过既定边界。

**非目标：**

- 不确定或编排阶段 07 三个冻结场景的参数、Ground Truth、RCA 或 Evaluation；阶段 04 只提供所需注入能力。
- 不实现阶段 05 的 Chat/Embedding/Rerank/RAG，也不实现阶段 06 的 AgentScope/A2A/产品 API。
- 不承诺 Loki、Tempo、Kubernetes、Cloud、更多语言或所有数据源 Adapter；新增测试 Adapter 只用于证明扩展边界。
- 不引入 Redis、内存伪持久化、通用 Extension Host、classpath 自动发现或运行时 Adapter 热替换。
- 不让模型提交任意 PromQL、URL、SQL、DSL、凭证或 Source 地址，也不向 Agent/Tool 提供 Docker socket。

## 设计决策

### 1. 以三个独立 Sample 应用保存业务所有权

`sample-gateway` 只负责路由和关联标识传递，订单与库存状态分别由 `order-service` 和 `inventory-service` 持有。两个业务服务使用阶段 03 的 `sample` schema、PostgreSQL 与 HikariCP，库存扣减使用乐观锁；应用启动、健康和镜像入口彼此独立。

选择独立应用而不是单进程模块，是为了产生真实跨服务 Trace、下游延迟和实例不可用行为。选择 PostgreSQL 而不是内存 Store，是为了让连接池耗尽和并发扣减合同可复现。Redis 不在设计范围内，加入它只会增加未经需求驱动的状态源。

### 2. 故障测试面由 Profile 和外部编排双重隔离

库存延迟、异常、线程阻塞 Controller 只由 `fault-lab`/`test` Profile 注册；生产 Profile 不是返回拒绝，而是根本不存在 `/internal/faults/*` 路由。订单服务默认 `maximumPoolSize=4`，提供恰好 4 个可受控长事务占用连接的测试能力。每个应用内故障都有幂等 reset，调用方在 `finally` 中恢复基线。

下游延迟保留 Toxiproxy，实例停止/恢复由 Fault Lab 在进程外控制原 `inventory-service` 容器。没有应用内“自杀”接口，也不把 Docker socket 交给 Agent 或 Tool。这样可证明应用级注入、网络故障和进程故障属于不同控制面。

### 3. 遥测后端是 Adapter 的实现细节

三个 Sample 服务通过 OpenTelemetry SDK/Agent 与 Collector 传播 HTTP、数据库和跨服务 Trace 上下文，暴露 Prometheus 的 HTTP/JVM/Hikari 指标，并将 allowlist 内的 `requestId/traceId/runId` 写入结构化 JSON 日志；时间统一为 UTC。Prometheus、Jaeger v1/v2 和 OTel Collector 在 Compose 中配置健康检查、保留期和内部网络。

core 只看到信号类型、`ObservationQuery`、`ObservationBatch`、Evidence 和 Port。Prometheus client、Jaeger DTO、Spring Actuator 类型及厂商分页状态只能存在于 Adapter 内部；架构测试扫描 core、Agent、RCA、Evaluation 和 Tool 合同，阻止泄漏。

### 4. Resource 身份与拓扑版本独立于运行实例细节

Target System、三个逻辑服务、数据库、Toxiproxy/外部依赖使用接入配置或平台分配的稳定 `resourceId`。临时 IP、容器 ID、Pod 名和 Java 类名只能作为受限属性或瞬时实例资源，不能替代逻辑身份。

静态 Compose 拓扑以带版本和生效时间的关系快照表达 `CALLS/DEPENDS_ON/READS_FROM/WRITES_TO/RUNS_ON` 等方向。Adapter 查询必须同时校验 Target、Run 与 Resource 归属；Evidence 保存其所依据的拓扑版本，使实例替换或拓扑切换后仍可回溯历史事实。

### 5. Adapter 实现集合与 Source 实例配置分开治理

`SourceAdapterRegistry` 由 Server composition root 显式装配 Adapter，以稳定 ID 检查重复，启动时执行共享合同探针，生成 capability snapshot 后冻结实现集合。运行时可以通过受控配置增删 Source 实例，但 Adapter ID/version 集合变化必须重启并生成新 snapshot。

Source 实例只保存 `connectionRef`，由 Secret Resolver 解析，不保存 URL、Token 或密码到 Observation、日志或模型上下文。同一 Resource/Signal 的多个 Source 必须声明 `PRIMARY/CORROBORATING/FALLBACK_DISABLED`。选择器按 Resource、信号、`queryTemplateId`、场景 required source 和配置优先级确定结果；技术失败不触发静默 failover，只有显式多源交叉验证才分别调用并保存结果。

### 6. 所有可观测 Adapter 共享单 Source、模板化查询合同

本阶段实现 `PrometheusMetricAdapter`、长期并行的 `JaegerV1TraceAdapter`/`JaegerV2TraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter`、独立 `HttpHealthAdapter` 和受控 Spring/文件配置 Adapter。每次调用只接受 Resource、信号、时间窗、版本化 `queryTemplateId + typed parameters`，由 Adapter 生成厂商查询；配置 Adapter 额外限制 allowlist key、快照大小和敏感字段。

Jaeger v1 与 v2 是同一 Trace 能力下的两个长期兼容边界，不是迁移前后的替换关系。两者使用独立稳定 Adapter ID/version、`connectionRef`、查询客户端、响应解码和健康探针，共享去厂商化查询模板、canonical Mapper、`ObservationBatch` 与 Evidence 合同。v1-only、v2-only 均可独立工作；v1+v2 只通过显式多 Source 查询分别产生 Batch，不因一方技术失败静默切换到另一方。同一 OTel Trace 经两个版本后端返回时由 Normalizer 去重事实并保留双方 provenance。Adapter 实现集合仍遵循 Registry 冻结和重启后发布新 capability snapshot 的规则。

一次调用只生成一个确定 Source 的 `ObservationBatch`。联邦入口放在 Batch source，实际底层来源放在每条 Record 的 `originSource`。成功无记录才生成合法空 Batch/`EMPTY`；未配置返回 `SOURCE_NOT_CONFIGURED`，不健康、超时、鉴权和取消等技术失败生成 `ChainFailure`，无效 Schema/来源/Artifact 返回 `OBSERVATION_BATCH_INVALID`。默认单条无效即拒绝整 Batch；只有 Adapter 合同显式允许时才保留部分结果并记录 rejected count、原因及 `complete=false`。

### 7. Observation 先校验、再持久审计、最后规范化为 Evidence

接收流水线依次校验 JSON Schema、Source READY、Adapter version、Target/Run/Resource 归属、UTC 时间窗/偏移、质量标记、脱敏、限长和 Artifact 访问/哈希。大响应先写 Artifact，再持久化引用。无法形成事实的 Observation 可以保留审计，但不能进入 EvidenceBundle 或 RCA 引用。

`EvidenceNormalizer` 生成不可变 Evidence 和 `provenanceRefs(kind=RUNTIME_OBSERVATION)`。去重键优先使用 OTel 原始 Trace/Span/metric/log 身份和派生关系，而不是仅比较摘要；同一 OTel 数据经 Prometheus 与 Jaeger 二次导出时只计算为一个事实，同时保留所有来源引用。不同来源的独立交叉验证保留为相关而非重复证据。

### 8. Tool 只编排 core Port 与 Normalizer

`LogQueryTool`、`MetricQueryTool`、`TraceQueryTool`、`HealthQueryTool`、`TopologyQueryTool`、`ConfigReadTool` 各自拥有输入 Schema 和受控参数映射，但不直接依赖厂商 Client/DTO。Tool 通过 Source 选择、Adapter Port、Observation 接收和 Normalizer 完成调用，只返回有界摘要、`observationBatchIds`、`evidenceBundleId`、Evidence/Artifact 引用以及 `SUCCEEDED/EMPTY/DENIED/FAILED`。

安全中间件继续执行既有 Schema、授权、审批、预算/deadline、Port、规范化/脱敏和审计顺序。Tool 不返回原始响应，也不允许后续分析直接依赖 ObservationBatch ID。

### 9. Compose smoke 只证明本阶段真实纵切

Compose 使用依赖健康条件组装 Sample、PostgreSQL、Prometheus、Jaeger v1/v2、OTel Collector、Toxiproxy 与必要的 OpsPilot 进程。smoke 分别覆盖 v1-only、v2-only、v1+v2，并按配置静态校验、镜像入口、Migration、依赖健康、业务请求、遥测可见、Adapter 查询、Observation、Evidence/Artifact、清理的固定顺序执行，保存退出码、日志、Artifact URI 与 SHA-256。

`.github/workflows/compose-smoke.yml` 不启动 Fake Provider 来声称模型、Agent 或完整 E2E 已通过；成功只代表阶段 04 的“业务请求 → 遥测后端 → Source Adapter → Observation → Evidence/Artifact”纵切成立。

## 风险与权衡

- [三个 Sample 应用与遥测后端同时引入，启动时序容易不稳定] → 所有依赖提供显式 healthcheck，smoke 按阶段等待并在失败时收集容器状态和日志。
- [测试 Profile 误入生产会暴露故障能力] → 使用条件 Bean/路由注册让生产路由不存在，并以路由表和黑盒 404 双重负向测试阻断镜像发布。
- [长事务或线程阻塞残留会污染后续测试] → 所有开关提供幂等 reset，测试编排使用 `finally`，清理后再次采样基线并验证连接池/线程恢复。
- [遥测异步导出导致时间窗内暂时无记录] → 使用有界等待与 UTC 时间对齐；超过 deadline 视为技术/完整性问题，不用伪造数据填补。
- [资源身份误用容器细节导致 Evidence 无法跨重启追溯] → 对身份字段建立 allowlist/禁用值测试，并以容器重建后的相同逻辑身份验收。
- [多 Source 配置诱发静默 failover 或重复计数] → 强制多源角色、记录每次独立调用和失败，Normalizer 使用原始派生身份去重并保留 provenance。
- [Jaeger v1 上游 EOL 增加镜像维护与安全风险] → 锁定可审计镜像、隔离运行网络并持续评估风险；该运维风险不改变 v1/v2 长期并行兼容合同，也不得通过删除 v1 规避兼容门禁。
- [大响应在数据库与 Artifact 之间留下失败窗口] → 复用阶段 03 的原子 Artifact 写入、哈希和访问校验；Observation 只在有效 Artifact 可读后成为事实。
- [Adapter 合同过度贴合首期产品] → 查询 DTO 与领域结果保持能力型，使用新增 Loki/Tempo 测试 Adapter 的架构变更集检查扩展性。
- [CI 资源限制使 Compose smoke 变慢或偶发失败] → 锁定镜像、设置有界超时/保留期、输出诊断 Artifact，并保持 smoke 与模型/E2E 门禁分离。

## 迁移与回滚计划

1. 确认阶段 03 完成门禁、数据库 migration、Artifact Adapter 和 Resource/Source/Observation/Evidence Repository 可用；否则保持本变更阻塞。
2. 依次交付 04-WP01～03，先在独立 Sample 集成测试中证明真实业务、故障恢复和关联遥测，不接入分析链。
3. 交付稳定 Resource/Topology 与 Source Registry，生成首个 capability snapshot；再逐个启用 Adapter 并运行共享合同测试，其中 Jaeger 必须分别通过 v1-only、v2-only、v1+v2 矩阵。
4. 启用 Observation 接收与 EvidenceNormalizer；对存量测试数据不回填 Evidence，新的 Batch 只在完整校验通过后进入事实层。
5. 注册六个 Tool，运行 Schema、安全中间件和架构依赖测试；最后启用 Compose 纵切和 CI smoke。
6. 部署失败时按相反顺序禁用 Tool/Source 实例并回滚应用与配置；不删除已保存的 Observation、Evidence 或 Artifact。Source 实例配置可回滚，Adapter 实现集合回滚必须重启并生成新 capability snapshot。

## 待确认事项

- 本阶段没有新增产品级待确认决策。锁定镜像 digest、合同版本或阶段 03 完成证据缺失时，相应工作包必须标记为 `BLOCKED`，不得以临时版本、Fake Source 或放宽校验继续。
- 若真实 Prometheus/Jaeger v1/v2/OTel 响应暴露现有 `observation-batch.schema.json` 无法表达的语义，应先提交兼容性分析；新增 `sourceKind` 只能作为 minor 扩展，改变 Resource 身份或 Evidence 引用语义必须升级 major 并通过 ADR。
