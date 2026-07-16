# 阶段 04：Sample System、故障接口与可观测适配

## 目标

实现首期 Java Sample System、真实日志/指标/Trace/健康/配置/拓扑采集链路、故障注入接口和去厂商化 Source Adapter，使每条现场 Evidence 能回溯到实际 Source、Observation 和 Artifact。

## 前置门禁

- 数据、Resource/Topology、Source、Observation、Evidence 和 Artifact 持久化已可用。
- 仍以跨语言/分布式目标为产品边界；Java/Spring 只作为首期验收实现。

## 实施内容

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
