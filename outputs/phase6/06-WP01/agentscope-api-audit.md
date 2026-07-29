# 06-WP01 AgentScope API 复核

复核日期：2026-07-28

## 结论

`PASS`。阶段 00 锁定的 Maven 组件仍可从项目本地 Maven 仓库解析，现有源码和测试继续使用同一坐标与框架边界；阶段 06 可以在该边界上实现唯一 `AgentExecutionService`，无需猜测或复制 ReAct 业务循环。

- Maven 坐标：`io.agentscope:agentscope-core:2.0.0`
- 固定源码 revision：`44c304ec84d5fbd8588c1af8bc71b1edb9663380`
- License：`Apache-2.0`
- 项目锁定位置：父 `pom.xml` 的 `agentscope.version=2.0.0`
- 本地复核对象：`.tmp/maven-repository/io/agentscope/agentscope-core/2.0.0/agentscope-core-2.0.0.jar`

## 真实 API 复核

阶段 00 已编译验证：

- `Model.stream(List<Msg>, List<ToolSchema>, GenerateOptions)`
- `AgentTool.callAsync(ToolCallParam)`
- `Toolkit.registerAgentTool(AgentTool)`
- `ReactConfig(int, boolean)`
- `ReActAgent.builder()` 及 `model`、`toolkit`、`maxIters`、`stopOnReject`

阶段 06 通过仓库内 JDK 21 的 `javap` 对同一 JAR 进一步复核：

- 执行：`ReActAgent.call(String, RuntimeContext)`、`call(List<Msg>, RuntimeContext)`
- 事件：`ReActAgent.streamEvents(..., RuntimeContext)`
- 中断：`interrupt()`、`interrupt(RuntimeContext)`、`interrupt(String, String)`
- Middleware：`ReActAgent.Builder.middleware(MiddlewareBase)` 与 `middlewares(...)`
- 状态：`Builder.stateStore(AgentStateStore)`、`defaultSessionId(String)`、`saveAgentState(RuntimeContext)`
- 会话：`RuntimeContext.Builder.userId(String)`、`sessionId(String)`、`agentState(...)`
- 调用边界：`MiddlewareBase.onAgent`、`onReasoning`、`onActing`、`onModelCall`

## 与阶段 00 证据的差异

阶段 00 报告只列出了构造 Agent、Model/Tool Adapter 与基础终止所需 API，没有把 Middleware、`RuntimeContext` 会话键、显式状态保存、事件流和各中断重载列入报告。实际锁定 JAR 已提供这些 API；这是证据覆盖面的补充，不是依赖漂移。

现有 `AgentScopeRuntimeAdapter` 仅准备真实 `ReActAgent.Builder`，尚未实现 core 唯一执行 Port、会话派生、父 deadline 阻塞边界和统一结果映射。这些是 WP01 的实施差距，不构成上游 API 阻塞。

## 边界决定

- core 只定义项目自有 request/decision/result/usage/checkpoint/event 合同，不引用 `io.agentscope.*`、Spring 或传输 DTO。
- Adapter 必须调用官方 `ReActAgent` 的 loop；不得在业务代码中增加第二个 `while`/`for` ReAct 循环。
- round、Model/Tool/Token 预算、deadline、取消和动作指纹在框架调用边界检查；终止后所有后续 Model/Tool 调用 fail closed。
- checkpoint 只使用配置的 PostgreSQL `AgentStateStore`，保存失败不得回退内存或文件。
- 持久事件只保存摘要、Usage、引用、指纹和关联 ID；隐藏推理、完整 Prompt、Secret 与未经授权正文不得进入状态或审计。

## 复核命令

```powershell
& '.tmp\jdk21\jdk-21.0.12+8\bin\javap.exe' `
  -classpath '.tmp\maven-repository\io\agentscope\agentscope-core\2.0.0\agentscope-core-2.0.0.jar' `
  'io.agentscope.core.ReActAgent' `
  'io.agentscope.core.ReActAgent$Builder' `
  'io.agentscope.core.agent.RuntimeContext' `
  'io.agentscope.core.middleware.MiddlewareBase' `
  'io.agentscope.core.state.AgentStateStore'
```
