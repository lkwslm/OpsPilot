# 阶段 04：Sample System、故障接口与可观测适配

## 目标

实现首期 Java Sample System、真实日志/指标/Trace/健康/配置/拓扑采集链路、故障注入接口和去厂商化 Source Adapter，使每条现场 Evidence 能回溯到实际 Source、Observation 和 Artifact。

## 前置门禁

- 数据、Resource/Topology、Source、Observation、Evidence 和 Artifact 持久化已可用。
- 仍以跨语言/分布式目标为产品边界；Java/Spring 只作为首期验收实现。

## 建设范围

1. 实现 `sample-gateway`、`order-service`、`inventory-service` 及既定订单/库存 API；使用 PostgreSQL、HikariCP、乐观锁，不加入 Redis。
2. 仅在 `fault-lab`/`test` Profile 注册 `/internal/faults/*`；生产 Profile 路由必须不存在。为 `inventory-service` 实现延迟、异常和线程阻塞测试开关，为 `order-service` 提供 maximumPoolSize=4、4 个受控长事务所需测试能力，但三个冻结场景的参数和编排留到阶段 07；下游延迟场景仍使用 Toxiproxy。实例停止不实现应用内开关，必须由 Fault Lab 在进程外停止并恢复原 `inventory-service` 容器。
3. 为 Sample 服务接入 OpenTelemetry、Prometheus、Jaeger 和结构化 JSON 日志；保留 HTTP/JVM/Hikari 指标和 UTC 时间。
4. 实现稳定 `ResourceRef`、版本化拓扑关系、Target System/Resource 注册和静态 Compose 拓扑；身份不得绑定临时 IP、容器 ID 或 Java 类名。
5. 实现 Phase 0 五类基线 `PrometheusMetricAdapter`、`JaegerTraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter`；同时按 Tool 映射补齐独立 `HttpHealthAdapter` 和受控 Spring/文件配置 Adapter。模型不能提交任意 PromQL、URL、SQL 或 DSL。
6. 实现专用 SourceAdapterRegistry：显式装配、稳定 ID、重复冲突失败、启动探针、能力快照和冻结；Source 选择由 Resource、信号、模板、场景 required source 和配置优先级确定，模型不能选择 URL。同一 Resource/Signal 的多 Source 必须声明 `PRIMARY/CORROBORATING/FALLBACK_DISABLED`，技术失败不得静默切换；Source 实例配置可以受控增删，但 Adapter 实现集合变化必须重启并生成新 capability snapshot。
7. 每次 Adapter 调用生成单一 Source 的 ObservationBatch；联邦来源必须在 Record 上保留 `originSource`。执行 Schema、Source READY/Adapter version、Resource 归属、脱敏、限长、时间对齐、质量标记、Artifact 哈希与访问权限校验；无法形成事实的 Observation 只保留审计，不得进入 RCA 引用。
8. 用 EvidenceNormalizer 把运行 Observation 转为不可变 Evidence/provenance；避免同一 OTel 数据经 Prometheus/Jaeger 二次导出后被重复计为独立证据。
9. 实现九个 Tool 中与本阶段相关的日志、指标、Trace、健康、拓扑和配置 Tool；Tool 只编排 core Port 和 Normalizer，不依赖厂商 DTO。
10. 在纵切可运行后补齐 `.github/workflows/compose-smoke.yml`：验证镜像入口、Compose config、依赖健康和 Migration，但不使用 Fake Provider 冒充模型/E2E。

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 04-WP01 | 总体范围、模块与 Sample System 边界 | 阶段 03 数据库基线 |
| 04-WP02 | Fault Lab 所有权、测试 Profile 和三个冻结场景的注入边界 | 04-WP01 |
| 04-WP03 | 可靠性/可观测性约束、分布式 Target 信号要求 | 04-WP01 |
| 04-WP04 | 分布式 Resource/Topology 模型、Evidence Bundle Schema | 阶段 03，04-WP01 |
| 04-WP05 | Source Registry、能力快照、来源选择与禁止静默切换规则 | 04-WP04 |
| 04-WP06 | Source Adapter 共享合同、Observation Batch Schema | 04-WP03～04-WP05 |
| 04-WP07 | Observation/Evidence provenance 与 Artifact 合同 | 04-WP06 |
| 04-WP08 | Tool 边界、Tool Contract Schema、Evidence-only 规则 | 04-WP06、04-WP07 |
| 04-WP09 | 冻结运行拓扑和 CI/Compose smoke 职责 | 04-WP01～04-WP08 |

### 04-WP01：实现三服务业务骨架与持久化

- **04-WP01.T1**：在 `sample-system/sample-gateway`、`sample-system/order-service`、`sample-system/inventory-service` 建立独立 Spring Boot 应用、镜像入口、健康端点和 UTC/JSON 日志基线。
- **04-WP01.T2**：实现冻结的订单、库存和网关 API；网关只做路由与关联标识传递，业务状态由订单/库存服务各自持有。
- **04-WP01.T3**：接入 PostgreSQL、HikariCP 和库存乐观锁，补齐 Schema/Migration；确认运行路径中没有 Redis 或内存伪持久化。
- **目标文件**：`sample-system/*/src/**`、`sample-system/*/pom.xml`、`sample-system/*/Dockerfile`、Sample Migration 与配置文件。
- **验证与证据**：三个服务的单元/集成测试、并发扣减冲突测试、API 响应样例、健康检查和镜像启动记录。

### 04-WP02：实现受控故障入口与环境恢复

- **04-WP02.T1**：只在 `fault-lab`/`test` Profile 注册 `inventory-service` 延迟、异常、线程阻塞控制器；生产 Profile 的路由表中必须不存在 `/internal/faults/*`。
- **04-WP02.T2**：为 `order-service` 提供可观测的连接池配置和 4 个受控长事务入口，默认 `maximumPoolSize=4`；场景参数由阶段 07 的 Fault Lab 注入。
- **04-WP02.T3**：配置 Toxiproxy 代理库存下游链路；容器停止/恢复只暴露给外部 Fault Lab，不在 Sample 应用中增加“自杀”接口。
- **04-WP02.T4**：为每种故障实现显式 reset，并验证异常退出时可由 `finally` 恢复基线。
- **目标文件**：Sample `fault`/`test` Profile、`fault-lab` 环境控制 Port/Adapter、`deployment/toxiproxy/**` 的 Toxiproxy 配置。
- **验证与证据**：Profile 路由差异测试、故障开关幂等测试、代理恢复测试和故障前后基线对比。

### 04-WP03：接入统一遥测与可关联上下文

- **04-WP03.T1**：为三个服务接入 OpenTelemetry SDK/Agent 和 Collector，确保 HTTP、数据库、跨服务调用的 Trace 上下文连续。
- **04-WP03.T2**：暴露 Prometheus HTTP/JVM/Hikari 指标，并把 `requestId/traceId/runId` 等允许字段写入结构化日志；所有时间使用 UTC。
- **04-WP03.T3**：在 Compose 中配置 Prometheus、Jaeger、OTel Collector 的健康检查、保留期和网络，不把遥测后端类型泄漏进 core。
- **目标文件**：Sample telemetry 配置、`deployment/prometheus/**`、`deployment/otel-collector/**`、`deployment/jaeger/**`、共享日志配置。
- **验证与证据**：一次订单调用对应的日志、指标、Trace 关联样本，以及 Collector/Prometheus/Jaeger 健康结果。

### 04-WP04：建立稳定资源身份与版本化拓扑

- **04-WP04.T1**：为 Target System、三个服务、数据库和外部依赖定义稳定 `ResourceRef`；禁止使用 IP、容器 ID、Pod 名或 Java 类名作为身份。
- **04-WP04.T2**：实现静态 Compose 拓扑加载、关系版本和生效时间，保存服务到服务、服务到数据库/代理的方向关系。
- **04-WP04.T3**：校验 Adapter 查询中的 Resource 都属于当前 Target/Run，并可从 Evidence 反查到拓扑版本。
- **目标文件**：Resource/Topology application service、Static Compose topology fixture、相关 Repository/Mapper。
- **验证与证据**：身份稳定性、跨 Target 拒绝、拓扑版本切换和历史 Evidence 可回溯测试。

### 04-WP05：实现 Source 注册、选择和能力冻结

- **04-WP05.T1**：实现 `SourceAdapterRegistry` 的显式装配、稳定 ID 去重、启动探针、能力快照和启动后冻结。
- **04-WP05.T2**：实现 Source 实例配置、Secret `connectionRef`、Resource/Signal 绑定，以及 `PRIMARY/CORROBORATING/FALLBACK_DISABLED` 关系校验。
- **04-WP05.T3**：按 Resource、信号、查询模板、场景 required source 和配置优先级做确定性选择；模型输入不得包含 URL，也不得在技术失败后静默换源。
- **04-WP05.T4**：允许受控增删 Source 实例；Adapter 实现集合变化必须重启并生成新的 capability snapshot。
- **目标文件**：Source Registry、配置 Schema/Mapper、capability probe/snapshot、Source selection service。
- **验证与证据**：重复 ID、required Source 缺失、探针失败、冻结后修改、选择优先级和无 failover 测试。

### 04-WP06：完成七类可观测 Source Adapter

- **04-WP06.T1**：实现 `PrometheusMetricAdapter`、`JaegerTraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter` 五类 Phase 0 基线。
- **04-WP06.T2**：实现独立 `HttpHealthAdapter` 与受控 Spring/文件配置 Adapter；配置只接受 allowlist key 和有界快照。
- **04-WP06.T3**：所有 Adapter 只接受受控查询 DTO/模板，禁止任意 PromQL、URL、SQL 或厂商 DSL；调用结果统一为单 Source `ObservationBatch`。
- **04-WP06.T4**：联邦记录保存 `originSource`；技术失败返回 `ChainFailure`，只有成功且无记录才返回 `EMPTY`。
- **目标文件**：`adapters/observability/**`、Adapter 查询/响应 Mapper、脱敏器与 fixture。
- **验证与证据**：共享合同覆盖成功、合法空、分页/限流、超时、鉴权、无效 Schema、部分结果、取消和来源追溯。

### 04-WP07：校验 Observation 并规范化 Evidence

- **04-WP07.T1**：在 Observation 接收边界校验 Schema、Source READY、Adapter version、Resource 归属、时间窗、质量标记、脱敏和限长。
- **04-WP07.T2**：对大响应先写 Artifact，校验哈希与访问权限后保存引用；无法形成事实的 Observation 只进入审计。
- **04-WP07.T3**：实现 `EvidenceNormalizer` 和不可变 provenance；用 OTel 原始身份/派生关系消除 Prometheus、Jaeger 二次导出造成的重复证据。
- **目标文件**：Observation validation pipeline、Evidence normalizer、Artifact integration、provenance/de-dup 规则。
- **验证与证据**：无效 Batch 拒绝、审计留存、Artifact 篡改拒绝、跨源去重和 Evidence 全链路回溯测试。

### 04-WP08：实现六个可观测 Tool

- **04-WP08.T1**：实现日志、指标、Trace、健康、拓扑、配置 Tool 的输入 Schema、边界校验、Port 编排和结果 DTO。
- **04-WP08.T2**：Tool 只返回有界摘要以及 Evidence/Artifact 引用，不返回厂商 DTO；结果状态限定为后续 Registry 合同所需的 `SUCCEEDED/EMPTY/DENIED/FAILED`。
- **04-WP08.T3**：验证 core、Agent、RCA、Evaluation 均不依赖 Prometheus、Jaeger、Spring 客户端类型。
- **目标文件**：Tool application layer、JSON Schema、Port mapper、架构依赖测试。
- **验证与证据**：六类 Tool 合同测试、越权/非法查询测试、依赖边界检查。

### 04-WP09：组装 Compose 纵切并建立 smoke 门禁

- **04-WP09.T1**：把 Sample、PostgreSQL、Prometheus、Jaeger、OTel Collector、Toxiproxy 与 OpsPilot 依赖组装进 Compose，所有依赖使用健康条件。
- **04-WP09.T2**：跑通“业务请求 → 遥测后端 → Source Adapter → Observation → Evidence/Artifact”的真实纵切并固化证据样本。
- **04-WP09.T3**：新增 `.github/workflows/compose-smoke.yml`，验证 Compose config、镜像入口、Migration 和依赖健康；不使用 Fake Provider 声称模型/E2E 通过。
- **目标文件**：`compose*.yml`、环境模板、smoke 脚本与 Workflow。
- **验证与证据**：本地与 CI smoke 日志、纵切 Evidence 链、容器健康和清理结果。

## 阶段内执行顺序

1. 先完成 04-WP01～04-WP03，得到有真实业务流量和遥测的 Sample 基线。
2. 04-WP04 与 04-WP05 固化资源、Source 身份和选择规则后，先完成 04-WP06，再由 04-WP07 校验 Observation 并规范化 Evidence；两者不得作为无依赖工作包并行关闭。
3. 04-WP08 只能消费已经稳定的 Port/Normalizer 合同；最后由 04-WP09 组装纵切。

## 测试与证据矩阵

| 验证层 | 必测内容 | 阶段证据 |
| --- | --- | --- |
| 单元/合同 | 资源身份、Source 选择、Adapter 合同、Observation 校验、Evidence 去重、Tool Schema | 测试报告与真实响应 fixture |
| 集成 | Sample + PostgreSQL、OTel/Prometheus/Jaeger、Artifact/Repository | Trace/指标/日志关联样本与数据库断言 |
| 安全 | 生产故障路由不存在、查询 allowlist、脱敏、跨 Target/Artifact 越权 | 负向测试报告 |
| Compose | 依赖健康、Toxiproxy、Migration、纵切与清理 | `compose-smoke` 日志及 Evidence provenance |

## 主要输出

- `sample-system` 三服务、共享观测配置和 fault/test-only Controller；
- Prometheus、OTel Collector、Jaeger、Toxiproxy 和 Sample 的 Compose 配置；
- Resource/Topology、五类 Phase 0 基线 Adapter，以及独立 HTTP 健康与受控配置 Adapter；
- Source Registry、EvidenceNormalizer 运行路径和六个可观测 Tool；
- Adapter 共享合同测试、真实响应 fixture/Artifact 和 Compose smoke Workflow。

## 完成门禁

- 三服务 API 和健康检查可调用；生产 Profile 不存在故障路由。
- Prometheus、Jaeger、JSONL、Actuator、Compose 的真实响应通过 ObservationBatch Schema，并记录正确 sourceKind/sourceId/adapterVersion/connectionRef/scope/query hash。
- HTTP 健康与受控配置 Adapter 通过相同共享合同测试；配置读取只接受 allowlist key 和有界快照。
- Source 停止、超时、鉴权失败返回 ChainFailure，不伪装为空 Batch且不静默换源；成功无记录才是 `EMPTY`。
- 所有首期 Adapter 的共享合同覆盖真实成功、合法空结果、分页/限流、超时、鉴权失败、无效 Schema、合同允许时的部分结果、取消、脱敏、Artifact 哈希和来源追溯；Source 未配置返回 `SOURCE_NOT_CONFIGURED`，无效 Batch 返回 `OBSERVATION_BATCH_INVALID`。
- 每条 Evidence 可回溯到 Batch、Record、Source 和有效 Artifact；跨源派生重复不会重复计数。
- core、Agent、RCA 和 Evaluation 中不存在 Prometheus、Jaeger、Spring DTO 或客户端类型。
- 新增测试 Loki/Tempo Adapter 只需 Adapter、配置和合同测试，core/状态机/Evidence/RCA/A2A major version 无修改。

## 明确不做

- 不承诺 Loki、Tempo、Kubernetes、Cloud 或所有语言 Adapter 已实现。
- 不让 Agent 或 Tool 获取 Docker socket；只有 Fault Lab 可控制故障环境。

## 设计依据

- [目标、Sample 与总体数据流](../design/opspilot-system-design/01-overview-and-scope.md)
- [模块、Sample、Fault Lab 与 Tool](../design/opspilot-system-design/02-modules-and-boundaries.md)
- [分布式目标与 Source Adapter](../design/opspilot-system-design/16-distributed-target-and-observability-adapters.md)
