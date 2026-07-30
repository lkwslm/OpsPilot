## Purpose

定义 `service-instance-stopped-inventory/1.0.0` 对原库存容器的冻结停机、卷保留、健康/业务/Prometheus 证明和同一服务恢复合同。

## ADDED Requirements

### Requirement: 原库存实例按冻结方式停止并保留数据
Fault Lab MUST 记录原 `inventory-service` 容器身份，停止该容器 90 秒且不删除卷，并在总计 240 秒的订单与库存持续负载中执行；恢复 MUST 启动原配置服务且不得以新空卷替代。

#### Scenario: 停机动作请求删除卷
- **GIVEN** 注入计划包含 `down -v`、volume remove 或等价数据删除动作
- **WHEN** Fault Lab 校验容器控制计划
- **THEN** 动作被 fail closed 拒绝，原容器和卷身份记录保持不变

### Requirement: 健康、连接和 target 共同证明实例停机
基线 MUST 满足 inventory readiness UP、Prometheus target UP 且成功率不低于 99%；故障窗 MUST 出现 readiness 不可达、连接拒绝或 503，并使 Prometheus target DOWN 至少 30 秒；恢复后 readiness MUST 在 60 秒内 UP，随后 60 秒业务成功率不低于 99% 且数据保持。

#### Scenario: readiness 不可达但 target 未持续 DOWN
- **GIVEN** inventory readiness 短暂不可达但 Prometheus target 未连续 DOWN 30 秒
- **WHEN** ScenarioValidator 判断故障成立条件
- **THEN** 场景数据集无效并保留观测时间线供诊断，不将短暂抖动判为实例停止

### Requirement: 实例停止 Evidence 与禁止误判固定
有效数据集 MUST 包含 `health.inventory.unreachable`、`log.order.inventory_connection_failed`，并至少包含 `metric.prometheus.inventory_target_down` 或 `trace.order.inventory_span_error`；MUST 排除单纯慢响应、数据库连接池耗尽和库存业务校验失败。

#### Scenario: 恢复后业务数据丢失
- **GIVEN** readiness 和 target 已恢复但卷 digest 或冻结业务记录发生丢失
- **WHEN** Fault Lab 完成恢复条件校验
- **THEN** 数据集被判为无效，且恢复报告明确标记数据保留失败
