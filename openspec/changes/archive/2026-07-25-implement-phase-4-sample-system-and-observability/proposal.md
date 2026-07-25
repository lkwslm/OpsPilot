## 背景与动机

阶段 03 已提供 Resource、Topology、Source、Observation、Evidence 与 Artifact 的持久化基础，但系统仍缺少可产生真实业务流量的 Sample System 和把现场遥测规范化为可追溯 Evidence 的运行链路。阶段 04 需要在不把 Java、Spring 或厂商协议泄漏到核心的前提下，打通首期 Sample、故障入口、可观测 Source、Adapter、Tool 与 Compose smoke 纵切，为后续 Provider、Agent 和故障场景编排提供可信输入。

## 变更内容

- 建立 `sample-gateway`、`order-service`、`inventory-service` 三个独立 Spring Boot 应用，实现冻结的订单/库存 API、PostgreSQL/HikariCP 持久化和库存乐观锁，不引入 Redis 或内存伪持久化。
- 仅在 `fault-lab`/`test` Profile 暴露库存延迟、异常、线程阻塞和订单长事务测试能力，提供显式 reset，并通过 Toxiproxy 与进程外容器控制保留下游延迟、实例停止/恢复边界。
- 为 Sample 服务接入 OpenTelemetry、Prometheus、长期并行兼容的 Jaeger v1/v2、结构化 JSONL 日志和 UTC 关联上下文，保留 HTTP/JVM/Hikari 指标并配置后端健康与保留期。
- 建立稳定 `ResourceRef`、Target/Resource 注册与版本化静态 Compose 拓扑，禁止以临时 IP、容器 ID、Pod 名或 Java 类名作为资源身份。
- 实现专用 `SourceAdapterRegistry`、Source 实例配置、启动探针、能力快照、冻结和确定性选择；多 Source 显式区分 `PRIMARY/CORROBORATING/FALLBACK_DISABLED`，技术失败不得静默换源。
- 实现 `PrometheusMetricAdapter`、长期并行的 `JaegerV1TraceAdapter`/`JaegerV2TraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter`、独立 `HttpHealthAdapter` 和受控 Spring/文件配置 Adapter；Jaeger v2 不替代或退役 v1，所有查询使用模板化 DTO，禁止任意 PromQL、URL、SQL 或厂商 DSL。
- 在 Observation 接收边界执行 Schema、Source/Adapter、Resource、时间、质量、脱敏、限长、Artifact 完整性与权限校验，再通过 `EvidenceNormalizer` 生成不可变 Evidence/provenance，并消除同一 OTel 数据的跨后端派生重复。
- 实现日志、指标、Trace、健康、拓扑和配置六个去厂商化 Tool，只编排 core Port 与 Normalizer，并只返回有界摘要、Evidence/Artifact 引用和稳定状态。
- 组装 Sample、PostgreSQL、Prometheus、Jaeger v1/v2、OTel Collector、Toxiproxy 与 OpsPilot 依赖的 Compose 纵切，以 v1-only、v2-only、v1+v2 三种配置运行 `compose-smoke`，验证配置、镜像入口、Migration、依赖健康和真实 Evidence 链。
- 不编排阶段 07 的三个冻结故障场景参数，不实现阶段 05 的 Provider/RAG 或阶段 06 的 Agent/A2A 产品能力，不承诺 Loki、Tempo、Kubernetes、Cloud 或所有语言 Adapter，也不向 Agent/Tool 暴露 Docker socket。

## 能力范围

### 新增能力

- `sample-system-business-flow`：三服务业务边界、冻结 API、PostgreSQL/HikariCP 持久化、库存乐观锁、健康与镜像启动合同。
- `controlled-fault-surface`：test-only 故障路由、受控长事务、Toxiproxy、进程外实例控制及幂等恢复合同。
- `sample-observability-pipeline`：Sample 的 OTel/Prometheus/Jaeger v1/v2/JSONL 接入、UTC 关联字段、后端健康与保留期合同。
- `stable-resource-topology`：稳定资源身份、Target/Resource 注册、版本化静态拓扑、归属校验及历史追溯合同。
- `observability-source-registry`：Source/Adapter 显式注册、探针、能力快照、冻结、多源角色及确定性选择合同。
- `observability-source-adapters`：七类首期 Adapter 能力、Jaeger v1/v2 长期并行兼容、受控查询、单 Source `ObservationBatch`、联邦来源和稳定失败语义合同。
- `runtime-evidence-normalization`：Observation 校验、Artifact 完整性、审计隔离、不可变 provenance 与跨源派生去重合同。
- `observability-query-tools`：日志、指标、Trace、健康、拓扑和配置 Tool 的 Schema、Port 编排、结果状态及去厂商化边界合同。
- `compose-observability-smoke`：真实 Compose 纵切、依赖健康、Migration、Evidence 链和 CI smoke 证据合同。

### 修改能力

- 无。本阶段消费阶段 01～03 已冻结的机器合同、核心 Port 与持久化能力，不改变其既有需求语义。

## 影响范围

- 主要代码：`sample-system/*`、`opspilot-adapters/observability/**`、Resource/Topology 与 Source application service、Observation validation、`EvidenceNormalizer`、`opspilot-tools-default` 和 Server composition root。
- 主要配置：Sample telemetry、`deployment/prometheus/**`、`deployment/otel-collector/**`、`deployment/jaeger/**` 中并行的 v1/v2 配置、`deployment/toxiproxy/**`、Compose 文件、环境模板与 `.github/workflows/compose-smoke.yml`。
- 主要运行依赖：PostgreSQL、Prometheus、Jaeger v1/v2、OpenTelemetry Collector、Toxiproxy、Docker Compose 及阶段 03 的 Repository/Artifact 能力。
- 对外与机器合同继续以 `docs/design/contracts/` 为最高事实源；Java/Spring 仅是首期验收实现，core、Agent、RCA、Evaluation、A2A 与 Tool 合同不得依赖厂商 DTO 或任意查询语言。
