## ADDED Requirements

### Requirement: 六组核心专项报告
核心验收 MUST 分别生成状态、快照、调度、Evidence、Middleware 和事务六组独立测试报告，任一组失败都不得关闭阶段门禁。

#### Scenario: 单组报告失败
- **GIVEN** 五组测试通过而事务组存在失败
- **WHEN** 汇总阶段 02 核心验收
- **THEN** 阶段门禁失败并准确标记事务工作包，其他报告仍可追溯

### Requirement: 无基础设施最小恢复链
测试 Port MUST 能完成正常、空知识、输入中断、预算耗尽、`NO_PROGRESS`、关键失败、取消和恢复路径，且无需 Spring 容器、数据库、AgentScope 或 A2A HTTP。

#### Scenario: 纯测试 Port 恢复运行
- **GIVEN** 一个在 `WAITING_INPUT` checkpoint 暂停的 Run 和合法用户输入
- **WHEN** 使用测试 Port 恢复并执行到受控终态
- **THEN** 状态迁移和审计确定可重放，整个测试未启动外部基础设施

### Requirement: 核心依赖证据
验收 MUST 扫描 core 依赖树和字节码导入，证明不存在 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 或厂商依赖。

#### Scenario: 禁用依赖进入传递依赖树
- **GIVEN** core 因传递依赖包含任一禁用库
- **WHEN** 执行依赖树与字节码扫描
- **THEN** 报告定位来源并使 core 专项构建失败

### Requirement: 边界所有权与证据身份
系统 MUST 输出 composition root、Repository 所有权、事务边界和扩展点清单，并明确测试实现不得在生产 Profile 注册；根构建和 core 专项构建证据 MUST 包含 commit、命令、退出码和 SHA-256。

#### Scenario: 从干净环境生成可复现证据
- **GIVEN** 阶段 02 实现和干净构建环境
- **WHEN** 执行根构建、core 专项构建及边界清单检查
- **THEN** 所有报告绑定同一 commit，记录完整命令、退出码和内容 SHA-256，缺少任一身份字段都会使验收失败

### Requirement: 测试 Port 可替换性
替换任一 core 测试 Port 实现时 core 生产代码 MUST 保持零差异，Adapter 实现之间 MUST 无依赖且 Server MUST 保持唯一 composition root。

#### Scenario: 替换 Repository 测试实现
- **GIVEN** 两个满足同一 Repository Port 合同的测试实现
- **WHEN** 在验收套件中切换实现
- **THEN** core 生产源码无需修改且全部共享合同测试保持通过
