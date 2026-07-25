# Purpose

定义阶段 04 Sample 系统的服务职责、真实持久化边界与可验证健康语义。

## Requirements

### Requirement: 三服务保持独立业务所有权
系统 MUST 将 `sample-gateway`、`order-service` 和 `inventory-service` 实现为可独立启动、构建镜像和检查健康的 Spring Boot 应用；网关 MUST 只路由冻结的订单/库存 API 并传递关联标识，订单与库存状态 MUST 分别由对应业务服务持有。

#### Scenario: 网关调用订单与库存业务
- **GIVEN** 三个 Sample 服务和其依赖均健康
- **WHEN** 客户端通过网关执行冻结的下单与库存查询 API
- **THEN** 网关传递关联标识且不保存业务状态，订单和库存服务分别完成其职责并返回合同约定的响应

### Requirement: Sample 业务状态使用真实 PostgreSQL
订单和库存服务 MUST 使用阶段 03 的 PostgreSQL `sample` schema 与 HikariCP 持久化业务状态，库存扣减 MUST 使用乐观锁保护并发不变量；运行路径 MUST NOT 使用 Redis 或内存 Store 伪装持久化。

#### Scenario: 并发库存扣减发生版本冲突
- **GIVEN** 两个并发请求读取同一库存版本且剩余库存只允许一个请求成功
- **WHEN** 两个请求提交扣减
- **THEN** 只有一个事务更新成功，另一个得到稳定冲突结果，数据库库存不为负且没有 Redis 或内存状态参与裁决

### Requirement: 镜像入口与健康状态可验证
每个 Sample 服务 MUST 提供可由 Compose 健康检查调用的健康端点、明确镜像入口和 UTC 运行配置；依赖未就绪或 Migration 不匹配时 MUST NOT 报告可接收业务流量。

#### Scenario: 数据库尚未就绪
- **GIVEN** Sample 服务进程已启动但 PostgreSQL 或所需 Migration 未就绪
- **WHEN** Compose 调用服务健康端点
- **THEN** 服务报告非健康状态且业务流量不会被判定为可用，依赖恢复后健康检查可转为成功
