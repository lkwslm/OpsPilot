# RCA

- Schema: 1.0.0
- Run: 306a124e-661b-4717-be22-04318947ac42
- Outcome: CONCLUSIVE
- Source-Digest: 1f76817c3a922a28d087e422bdb27e210a9c43f11e6b5fde86d07becca5b1f8e

## Summary

Canonical evidence for incident c97f6163: health.inventory.unreachable (bc584362-6969-403a-90e5-1dc4b7a93680), log.order.inventory_connection_failed (5f76a2a9-e2ed-400c-b27f-f6cafbda06f5), and metric.prometheus.inventory_target_down (3af57a2d-8eea-4edc-9d0a-930ad0a41e4e). Code analysis identified RETRY_TIMEOUT markers in order-service paths, and knowledge search returned three relevant references. Diagnosis concluded root cause service.instance.stopped.inventory with 0.93 confidence and no conflicting evidence; listed missing evidence codes do not contradict the supported root cause. Remediation actions are restore.inventory.instance, verify.health.probes, and add.instance.availability.alert.

## Root Cause

service.instance.stopped.inventory

## Hypotheses

- REFUTED: Inventory dependency latency causing order failures
- REFUTED: Order database connection pool exhausted
- SUPPORTED: Inventory service instance stopped

## Citations

- health.inventory.unreachable (`bc584362-6969-403a-90e5-1dc4b7a93680`)
- log.order.inventory_connection_failed (`5f76a2a9-e2ed-400c-b27f-f6cafbda06f5`)
- metric.prometheus.inventory_target_down (`3af57a2d-8eea-4edc-9d0a-930ad0a41e4e`)

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

- Trace, configuration, and topology evidence returned no matching evidence in the collection window; no inference was made from those absences beyond listing required codes as missing.
- Code analysis findings are present but do not constitute canonical evidence codes and were not used to confirm root cause.
- Trace, configuration, and topology evidence were absent; connection-pool exhaustion was not supported. Recovery actions require platform approval and should preserve reversible rollback paths.
