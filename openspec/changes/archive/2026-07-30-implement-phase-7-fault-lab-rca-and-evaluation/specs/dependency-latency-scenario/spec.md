## Purpose

定义 `dependency-latency-inventory/1.0.0` 的冻结注入、负载、三窗口判定、Trace 拓扑、Evidence 与恢复行为，确保下游延迟不会被其他故障表象冒充。

## ADDED Requirements

### Requirement: 库存依赖延迟参数保持冻结
场景 MUST 通过 Toxiproxy 对 `order-service → inventory-service` 注入 `3000ms ± 100ms` 下游延迟 120 秒，并在总计 240 秒的持续负载中执行；任何参数漂移 MUST 在注入前被拒绝。

#### Scenario: 延迟参数超出容差
- **GIVEN** 场景配置请求 3200ms 延迟或不足 120 秒注入窗
- **WHEN** ScenarioValidator 校验冻结版本 `1.0.0`
- **THEN** 场景以冻结参数不匹配失败且不创建 toxic

### Requirement: 三窗口和 Trace 拓扑共同证明故障成立
场景 MUST 验证 60 秒基线至少 100 个订单请求、成功率不低于 99% 且 p95 小于 500ms；故障窗至少 30 个请求、order/inventory client span p95 不低于 2500ms、父子关系与时间窗正确且 inventory CPU/DB 不饱和；移除 toxic 后 60 秒内成功率 MUST 不低于 99% 且 p95 小于 700ms。

#### Scenario: HTTP 变慢但 Trace 父子关系错误
- **GIVEN** 请求延迟达到阈值但慢 span 不属于目标 order→inventory 父子链或不在 fault window
- **WHEN** 场景判定故障有效性
- **THEN** 数据集被判为无效，不得仅凭 HTTP 变慢宣称注入成功

### Requirement: 延迟场景 Evidence 与禁止误判固定
有效数据集 MUST 包含 `trace.order.inventory_span_latency_high`、`metric.gateway.request_latency_high`，并至少包含 `log.order.inventory_timeout_or_slow` 或 `metric.inventory.resource_normal`；MUST 将 `database.pool.exhausted.order` 和 `service.instance.stopped.inventory` 作为禁止根因验证。

#### Scenario: 缺少辅助 Evidence
- **GIVEN** 两项必选 Evidence 存在但两个辅助 Evidence 均未满足确定性 predicate
- **WHEN** Ground Truth 和场景校验器封存数据集
- **THEN** 数据集有效性失败并明确报告未满足的 one-of Evidence 组
