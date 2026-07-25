## ADDED Requirements

### Requirement: 故障路由只在测试 Profile 注册
`inventory-service` 的延迟、异常和线程阻塞控制器 MUST 只在 `fault-lab` 或 `test` Profile 注册；生产 Profile 的路由表 MUST NOT 存在任何 `/internal/faults/*` 路由，不能仅依靠运行时授权拒绝隐藏该路由。

#### Scenario: 生产 Profile 检查故障路由
- **GIVEN** `inventory-service` 使用生产 Profile 启动
- **WHEN** 检查应用路由表并请求 `/internal/faults/*`
- **THEN** 路由表中不存在故障端点且黑盒请求不能进入故障控制器

#### Scenario: 测试 Profile 启用库存故障
- **GIVEN** `inventory-service` 使用 `fault-lab` Profile 启动
- **WHEN** 受控调用方启用延迟、异常或线程阻塞之一
- **THEN** 对应故障可观测地生效，未启用的故障保持基线行为

### Requirement: 应用内故障可幂等恢复
每种应用内故障能力 MUST 提供显式、幂等的 reset；测试和 Fault Lab 调用 MUST 在 `finally` 等等价清理边界恢复基线，并在清理后验证线程、响应和连接池状态。

#### Scenario: 故障测试中途异常
- **GIVEN** 一个库存故障已经启用且测试编排在验证期间抛出异常
- **WHEN** 清理逻辑重复执行 reset
- **THEN** reset 每次均安全完成，服务恢复基线响应且不残留阻塞线程或故障状态

### Requirement: 连接池耗尽能力保持冻结规模
`order-service` MUST 将测试基线的 HikariCP `maximumPoolSize` 配置为 4，并提供 4 个可受控长事务同时占用连接的测试能力；具体持续时间、触发顺序和场景参数 MUST 留给阶段 07 的 Fault Lab。

#### Scenario: 四个事务占满连接池
- **GIVEN** `order-service` 以故障测试 Profile 运行且连接池最大连接数为 4
- **WHEN** Fault Lab 同时保持 4 个受控长事务
- **THEN** 连接池占用可通过指标观察，场景参数没有硬编码到 Sample 应用，reset 后连接全部释放

### Requirement: 网络与实例故障由外部控制面负责
库存下游延迟 MUST 使用 Toxiproxy；停止与恢复实例 MUST 由 Fault Lab 在进程外操作原 `inventory-service` 容器。Sample 应用 MUST NOT 提供自杀接口，Agent 和 Tool MUST NOT 获得 Docker socket。

#### Scenario: 停止并恢复库存实例
- **GIVEN** Fault Lab 具有最小化的环境控制权限且 Agent/Tool 无容器控制权限
- **WHEN** Fault Lab 停止并恢复原 `inventory-service` 容器
- **THEN** 实例故障在进程外发生并恢复到原服务身份，Sample 路由中不存在自杀接口且 Agent/Tool 无法直接控制容器
