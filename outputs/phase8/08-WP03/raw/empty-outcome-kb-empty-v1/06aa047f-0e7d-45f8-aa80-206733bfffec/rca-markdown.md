# RCA

- Schema: 1.0.0
- Run: 06aa047f-0e7d-45f8-aa80-206733bfffec
- Outcome: CONCLUSIVE
- Source-Digest: eb8df7eb528f35a1f75886804a73888ad07a8acbf44ac2680fce883e67a4f92e

## Summary

Incident 52b1174c (run 06aa047f) is diagnosed as inventory-service outage: health readiness DOWN, order logs report INVENTORY_CONNECTION_FAILED, and Prometheus marks the inventory target down. Code analysis locates retry/timeout handling in order-service paths consistent with the inventory connection failure. No conflicting evidence or knowledge-base references were returned. Recommended actions: restore inventory instance, verify health probes, and add availability alerting.

## Root Cause

service.instance.stopped.inventory

## Hypotheses

- SUPPORTED: Inventory service instance is stopped or unreachable
- UNVERIFIED: Inventory service latency causes order inventory call failures

## Citations

- health.inventory.unreachable (`755549f4-9e75-4aa8-9636-e898e3759c0a`)
- log.order.inventory_connection_failed (`0501adf4-ae39-438a-bdaf-f31617396917`)
- metric.prometheus.inventory_target_down (`16724ecd-6c39-4d9f-bdc8-50dd4d3ea6af`)

## Evidence Assessment

- Coverage: 1.0
- Missing: trace.order.inventory_span_latency_high
- Missing: metric.gateway.request_latency_high
- Missing: metric.order.hikari_active_at_max
- Missing: metric.order.hikari_pending_positive
- Missing: log.order.connection_timeout

## Actions

### immediate
- restore.inventory.instance: 立即恢复故障组件
### longTerm
- verify.health.probes: 修复根因并固化配置
### monitoring
- add.instance.availability.alert: 增加可观测性告警
### tests
### humanNextSteps
### rollback

## Limitations

- TraceQueryTool, ConfigReadTool, and TopologyQueryTool returned NO_MATCH; those absences were not treated as evidence of a fact.
- No direct deployment or scheduler evidence was available to prove the stopped state; the conclusion is inferred from health, log, and metric signals.
- Diagnosis is conclusive only for inventory service outage; connection-pool, timeout, and latency remediations are not supported by current evidence.
