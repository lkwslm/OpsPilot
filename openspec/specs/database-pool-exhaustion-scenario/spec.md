## Purpose

定义 `database-pool-exhausted-order/1.0.0` 的冻结连接池、长事务、负载、Evidence、共享数据库保护和恢复合同，确保只制造订单服务局部池耗尽。

## Requirements

### Requirement: 连接池耗尽参数保持冻结
场景 MUST 在 `maximumPoolSize=4`、`connectionTimeout=2000ms` 的 order test profile 中启动 4 个受控长事务并持有 90 秒，故障注入窗为 120 秒且持续负载总计 240 秒；MUST NOT 停止、重启或破坏共享 PostgreSQL。

#### Scenario: 注入尝试停止共享 PostgreSQL
- **GIVEN** injector 计划包含停止数据库容器或撤销共享数据库可用性的动作
- **WHEN** Fault Lab 执行安全策略检查
- **THEN** 注入在副作用前被拒绝，数据集无效且 OpsPilot 状态库保持可用

### Requirement: 池状态和真实超时共同证明故障成立
基线 MUST 满足 Hikari pending=0、active<max、至少 100 个订单请求且成功率不低于 99%；故障窗 MUST 连续至少 15 秒满足 active=max、pending>0 并出现真实 connection timeout；释放长事务后 30 秒内 MUST 恢复 pending=0、active<max，60 秒内成功率不低于 99%。

#### Scenario: active 达上限但没有等待或超时
- **GIVEN** Hikari active=max 但 pending 始终为 0 且没有真实 connection timeout
- **WHEN** ScenarioValidator 判断 fault window
- **THEN** 不认定连接池耗尽成立并输出缺失的故障 predicate

### Requirement: 连接池场景 Evidence 与禁止误判固定
有效数据集 MUST 包含 `metric.order.hikari_active_at_max`、`metric.order.hikari_pending_positive` 和 `log.order.connection_timeout`，可包含 `trace.order.db_wait_high`、`config.order.hikari_pool_size_four`；MUST 排除 PostgreSQL 实例停止、inventory 实例停止和下游网络延迟根因。

#### Scenario: Evidence 属于错误服务
- **GIVEN** Evidence Code 相同但 Resource 或 service 指向非 order-service
- **WHEN** Ground Truth 规则匹配数据集 Evidence
- **THEN** 该 Evidence 不计入 required 集合且数据集不能通过必选 Evidence 门禁
