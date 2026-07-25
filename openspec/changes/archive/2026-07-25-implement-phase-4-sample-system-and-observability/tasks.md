## 1. 三服务业务骨架与持久化（04-WP01，依赖阶段 03）

- [x] 1.1 `04-WP01.T1` 在 `sample-system/` 下建立 `sample-gateway`、`order-service`、`inventory-service` 三个独立 Spring Boot 应用及聚合构建，补齐各自 `pom.xml`、Dockerfile、镜像入口、健康端点和 UTC/JSON 日志基线；执行 Sample 模块构建、镜像启动与健康检查并保存日志和镜像身份。
- [x] 1.2 `04-WP01.T2` 按冻结合同实现订单、库存和网关 API，让网关只做路由与 `requestId/traceId/runId` 关联标识传递，订单/库存服务分别持有业务状态；以单元/集成测试和 API 响应 fixture 证明成功、非法输入与下游失败边界。
- [x] 1.3 `04-WP01.T3` 将订单和库存接入阶段 03 的 PostgreSQL `sample` schema、HikariCP 与库存乐观锁，补齐版本化 Migration 和映射，移除运行路径中的 Redis/内存伪持久化；以 Testcontainers CRUD、事务回滚和并发扣减冲突测试证明库存不为负且只允许一个冲突写提交。

## 2. 受控故障入口与环境恢复（04-WP02，依赖 04-WP01）

- [x] 2.1 `04-WP02.T1` 仅在 `fault-lab`/`test` Profile 为 `inventory-service` 注册延迟、异常和线程阻塞 `/internal/faults/*` 控制器；以路由表与黑盒测试证明测试 Profile 可用、生产 Profile 路由完全不存在。
- [x] 2.2 `04-WP02.T2` 为 `order-service` 配置可观测的 HikariCP `maximumPoolSize=4` 和 4 个受控长事务测试入口，把持续时间、触发顺序和场景参数留给阶段 07；以连接池指标和并发测试证明恰好 4 个连接可被占用并在 reset 后释放。
- [x] 2.3 `04-WP02.T3` 在 `deployment/toxiproxy/` 配置库存下游代理，并在 `fault-lab` 的环境控制 Port/Adapter 中实现对原 `inventory-service` 容器的进程外停止与恢复；以代理延迟/恢复和容器身份恢复测试证明 Sample 无自杀接口、Agent/Tool 无 Docker socket。
- [x] 2.4 `04-WP02.T4` 为所有应用内故障和代理状态实现幂等 reset，在编排路径用 `finally` 恢复；以重复 reset、异常中止和故障前后基线对比证明无线程、连接、延迟或异常状态残留。

## 3. 统一遥测与关联上下文（04-WP03，依赖 04-WP01）

- [x] 3.1 `04-WP03.T1` 为三个 Sample 服务接入 OpenTelemetry SDK/Agent 与 Collector，覆盖 HTTP、数据库和跨服务调用上下文传播；以一次订单调用的 Trace fixture 证明网关、订单、库存和数据库 Span 连续。
- [x] 3.2 `04-WP03.T2` 暴露 Prometheus HTTP/JVM/Hikari 指标，并把 allowlist 内的 `requestId/traceId/runId` 写入结构化 JSONL，所有时间统一 UTC；以日志、指标、Trace 关联样本和 Secret 扫描证明可关联且无敏感字段泄漏。
- [x] 3.3 `04-WP03.T3` 在 `deployment/prometheus/`、`deployment/otel-collector/`、`deployment/jaeger/` 与 Compose 中配置内部网络、健康检查、保留期和有界等待；以 `docker compose config`、后端健康查询和导出失败负向测试证明遥测缺失不会被伪造为成功。

## 4. 稳定资源身份与版本化拓扑（04-WP04，依赖阶段 03 与 04-WP01）

- [x] 4.1 `04-WP04.T1` 为 Target System、三个逻辑服务、数据库、Toxiproxy 和外部依赖定义并注册稳定 `ResourceRef`，建立 identity allowlist/禁用值校验；以容器重建测试证明 IP、容器 ID、Pod 名和 Java 类名变化不会改变逻辑 SERVICE 身份。
- [x] 4.2 `04-WP04.T2` 实现静态 Compose 拓扑 fixture、加载 application service、方向关系、版本和生效时间，并通过阶段 03 Repository/Mapper 持久化历史快照；以版本切换测试证明新查询使用新拓扑、历史关系仍可回溯。
- [x] 4.3 `04-WP04.T3` 在 Source 选择、Adapter 查询和 Evidence provenance 入口校验 Target/Run/Resource 归属并绑定拓扑版本；以跨 Target/Run/未知 Resource 拒绝和历史 Evidence 回查测试证明外部调用前 fail closed。

## 5. Source 注册、选择与能力冻结（04-WP05，依赖 04-WP04）

- [x] 5.1 `04-WP05.T1` 实现 `SourceAdapterRegistry` 的 composition-root 显式装配、稳定 ID 去重、共享合同启动探针、capability snapshot 和启动后冻结；以重复 ID、探针失败、部分快照拒绝和冻结后修改测试验收。
- [x] 5.2 `04-WP05.T2` 实现 Source 实例配置 Schema/Mapper、Secret `connectionRef` 解析、Resource/Signal 绑定及 `PRIMARY/CORROBORATING/FALLBACK_DISABLED` 关系校验；以未声明/冲突角色、Secret 泄漏和越权解析负向测试验收。
- [x] 5.3 `04-WP05.T3` 实现按 Resource、信号、`queryTemplateId`、场景 required source、READY 状态和配置优先级的确定性选择，禁止模型 URL 与技术失败静默换源；以 required Source 缺失、优先级、PRIMARY 超时、多源显式查询和无 failover 测试验收。
- [x] 5.4 `04-WP05.T4` 实现 Source 实例配置的受控增删与审计，并让 Adapter ID/version 实现集合变化必须重启后生成新 capability snapshot；以配置热更新成功、实现热替换拒绝和重启快照版本变化测试验收。

## 6. 七类可观测 Source Adapter（04-WP06，依赖 04-WP03～05）

- [x] 6.1 `04-WP06.T1` 在 `opspilot-adapters/observability` 实现 `PrometheusMetricAdapter`、`JaegerTraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter` 五类 Phase 0 基线及响应 Mapper/fixture；以真实响应通过 ObservationBatch Schema 和共享合同套件验收。
- [x] 6.2 `04-WP06.T2` 实现独立 `HttpHealthAdapter` 与受控 Spring/文件配置 Adapter，对配置 key、层级、快照大小和敏感字段使用 allowlist；以合法快照、非 allowlist key、超限和脱敏测试验收。
- [x] 6.3 `04-WP06.T3` 为全部 Adapter 实现版本化 `queryTemplateId + typed parameters` 映射和单 Source `ObservationBatch`，禁止任意 PromQL、URL、SQL/DSL，完整记录 `sourceKind/sourceId/adapterVersion/connectionRef/scope/query hash`；以注入负向测试和 Prometheus/Jaeger 独立 Batch 测试验收。
- [x] 6.4 `04-WP06.T4` 实现联邦 Record `originSource`、`SOURCE_NOT_CONFIGURED`、`ChainFailure`、合法 `EMPTY`、`OBSERVATION_BATCH_INVALID` 与显式部分结果语义；让共享合同覆盖成功、空、分页/限流、超时、鉴权、无效 Schema、部分结果、取消、脱敏、Artifact 哈希和来源追溯。
- [x] 6.5 `04-WP06.T5` 将 Jaeger Trace Source 拆分为长期并行支持的 v1/v2 版本适配边界，使用独立稳定 Adapter ID/version、`connectionRef`、查询客户端和健康探针，共享厂商无关查询模板、canonical Mapper 与 `ObservationBatch`；不得以 v2 上线或 v1 上游 EOL 为由退役 v1，并以 v1-only、v2-only、v1+v2 的 Compose/合同矩阵验证单版本查询、显式多 Source 查询、无静默 failover、同一 OTel Trace 去重及双 provenance 保留。

## 7. Observation 校验与 Evidence 规范化（04-WP07，依赖 04-WP06）

- [x] 7.1 `04-WP07.T1` 实现 Observation validation pipeline，依次校验 Schema、Source READY、Adapter ID/version、Target/Run/Resource、UTC 时间窗/偏移、freshness/completeness/sampling/truncation、脱敏和限长；以每个拒绝分支和合同允许部分结果的固定 fixture 验收。
- [x] 7.2 `04-WP07.T2` 将超限原始响应先写入 `ArtifactAccessService`，在保存 Observation 引用前校验权限、Run/Task 归属、媒体类型、大小和 SHA-256；将无法形成事实的 Observation 仅作安全审计，以篡改、越权、超限和审计不入 Evidence 测试验收。
- [x] 7.3 `04-WP07.T3` 实现 `EvidenceNormalizer.normalizeRuntime`、不可变 `provenanceRefs` 及基于 OTel 原始身份/派生关系的同源与跨源去重；以 Batch→Record→Source→Artifact→拓扑版本全链路回溯、Source 更新后历史不变和 Prometheus/Jaeger 派生重复不重复计数测试验收。

## 8. 六个去厂商化可观测 Tool（04-WP08，依赖 04-WP06～07）

- [x] 8.1 `04-WP08.T1` 在 `opspilot-tools-default` 实现日志、指标、Trace、健康、拓扑、配置 Tool 的版本化输入 Schema、Resource/时间窗/模板边界校验、core Port 编排和稳定结果 DTO；以六类成功合同和未知字段、超大时间窗、跨 Target 查询测试验收。
- [x] 8.2 `04-WP08.T2` 让 Tool 只返回有界摘要、`observationBatchIds/evidenceBundleId`、Evidence/Artifact 引用和 `SUCCEEDED/EMPTY/DENIED/FAILED`，不返回原始 Source 响应或厂商 DTO；以大响应 Artifact、合法空、前置拒绝和技术失败状态测试验收。
- [x] 8.3 `04-WP08.T3` 建立架构与扩展门禁，证明 Tool 只依赖 core Port/Normalizer，core、Agent、RCA、Evaluation 无 Prometheus/Jaeger/Spring 客户端类型；用测试 Loki/Tempo Adapter 的最小变更集证明无需修改 core、状态机、Evidence/RCA 表或 A2A major version。

## 9. Compose 纵切与 smoke 门禁（04-WP09，依赖 04-WP01～08）

- [x] 9.1 `04-WP09.T1` 在 Compose 与环境模板中组装三个 Sample、PostgreSQL、Prometheus、Jaeger、OTel Collector、Toxiproxy 和本阶段所需 OpsPilot 依赖，所有启动依赖使用健康条件；以干净环境的 `docker compose config`、镜像入口、Migration、健康和失败诊断验收。
- [x] 9.2 `04-WP09.T2` 跑通并固化“业务请求 → 遥测后端 → Source Adapter → Observation → Evidence/Artifact”真实纵切，记录命令、退出码、日志/报告 URI、SHA-256 和 capability snapshot；以关联样本、Schema 校验、provenance 回溯及成功/失败后幂等清理验收。
- [x] 9.3 `04-WP09.T3` 新增 `.github/workflows/compose-smoke.yml`，在 CI 验证 Compose config、镜像入口、Migration、依赖健康、阶段 04 纵切和清理并上传失败诊断 Artifact；明确禁止 Fake Provider 冒充模型/Agent/RCA/Evaluation/E2E 通过，并以本地与 CI smoke 证据完成阶段门禁审查。
