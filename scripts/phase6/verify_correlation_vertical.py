#!/usr/bin/env python3
"""Exercise the real six-process Phase 6 correlation vertical and capture evidence."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def request(method: str, url: str, body: dict | None = None, headers: dict | None = None):
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=payload, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, dict(response.headers), response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode()


def compose(project: str, *args: str) -> str:
    command = ["docker", "compose", "-p", project, "-f",
               str(ROOT / "deployment" / "docker-compose.yml"), *args]
    return subprocess.run(command, cwd=ROOT, check=True, text=True,
                          encoding="utf-8", errors="replace",
                          capture_output=True).stdout


def header(headers: dict, name: str) -> str | None:
    return next((value for key, value in headers.items() if key.lower() == name.lower()), None)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default="opspilot-wp10")
    parser.add_argument("--output", default="outputs/phase6/06-WP10")
    args = parser.parse_args()
    output = ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)

    for _ in range(60):
        status, _, _ = request("GET", "http://127.0.0.1:8080/actuator/health/readiness")
        if status == 200:
            break
        time.sleep(1)
    else:
        raise RuntimeError("product readiness did not become healthy")

    principal = "phase6-compose-tenant"
    execution = uuid.uuid4().hex[:12]
    common = {"Content-Type": "application/json", "X-Principal-Id": principal}
    packets = []
    create_status, create_headers, create_body = request("POST", "http://127.0.0.1:8080/api/incidents", {
        "targetSystemId": "sample-system", "resourceIds": ["service:sample-system"],
        "title": "Phase 6 correlation vertical", "severity": "HIGH", "ticket": {"id": "P6-10"}
    }, {**common, "Idempotency-Key": f"phase6-create-{execution}"})
    packets.append({"hop": "REST create", "status": create_status,
                    "requestId": header(create_headers, "X-Request-Id"),
                    "traceId": header(create_headers, "Trace-Id"), "body": json.loads(create_body)})
    if create_status != 201:
        raise RuntimeError(f"create failed: {create_status} {create_body}")
    incident_id = json.loads(create_body)["incidentId"]

    start_status, start_headers, start_body = request(
        "POST", f"http://127.0.0.1:8080/api/incidents/{incident_id}/run",
        {"modelConfigVersion": "phase6-models-v1", "evaluationProfile": "mvp-v1",
         "tokenBudget": 1000, "deadlineSeconds": 600},
        {**common, "Idempotency-Key": f"phase6-start-{execution}"})
    if start_status != 202:
        raise RuntimeError(f"start failed: {start_status} {start_body}")
    run_id = json.loads(start_body)["runId"]
    request_id = header(start_headers, "X-Request-Id")
    trace_id = header(start_headers, "Trace-Id")
    if request_id is None or trace_id is None:
        raise RuntimeError("start response omitted correlation headers")
    step_id = str(uuid.uuid4())
    invocation_id = str(uuid.uuid4())
    packets.append({"hop": "REST start", "status": start_status, "requestId": request_id,
                    "traceId": trace_id, "incidentId": incident_id, "runId": run_id,
                    "body": json.loads(start_body)})

    token = (ROOT / ".tmp" / "secrets" / "supervisor-service-token.txt").read_text().strip()
    a2a_headers = {
        "Authorization": f"Bearer {token}", "Content-Type": "application/json",
        "X-A2A-Skill": "supervise-incident", "X-DB-Role": "opspilot_app_role",
        "X-Incident-Id": incident_id, "X-Run-Id": run_id, "X-Step-Id": step_id,
        "X-Request-Id": request_id, "Trace-Id": trace_id,
        "X-Invocation-Id": invocation_id, "X-Phase6-Vertical-Slice": "true",
    }
    a2a_status, a2a_response_headers, a2a_body = request(
        "POST", "http://127.0.0.1:8080/a2a/messages:send", {}, a2a_headers)
    if a2a_status != 200:
        raise RuntimeError(f"A2A vertical failed: {a2a_status} {a2a_body}")
    a2a = json.loads(a2a_body)
    packets.append({"hop": "Supervisor -> professional A2A -> Tool/Provider",
                    "status": a2a_status, "requestId": header(a2a_response_headers, "X-Request-Id"),
                    "traceId": header(a2a_response_headers, "Trace-Id"), "body": a2a})

    report_status, _, report_body = request(
        "GET", f"http://127.0.0.1:8080/api/incidents/{incident_id}/report?runId={run_id}",
        headers={"X-Principal-Id": principal, "Accept": "application/json"})
    sse_status, _, sse_body = request(
        "GET", f"http://127.0.0.1:8080/api/incidents/{incident_id}/events?runId={run_id}",
        headers={"X-Principal-Id": principal})
    packets.extend([
        {"hop": "Evidence -> RCA", "status": report_status, "body": json.loads(report_body)},
        {"hop": "RCA -> SSE", "status": sse_status, "body": sse_body},
    ])
    if report_status != 200 or sse_status != 200 or "RCA_COMPLETED" not in sse_body:
        raise RuntimeError("RCA/SSE contract did not complete")

    sql = f"""SELECT coalesce(json_agg(row_to_json(q)),'[]'::json)::text FROM (
        SELECT audit_id,parent_audit_id,boundary,principal_id,incident_id,run_id,step_id,
               request_id,trace_id,a2a_task_id,invocation_id,result_code,error_code,
               summary,log_artifact_id,occurred_at
        FROM opspilot.correlation_audit WHERE principal_id='{principal}'
          AND incident_id='{incident_id}' AND request_id='{request_id}'
        ORDER BY occurred_at,audit_id) q;"""
    graph_rows = json.loads(compose(args.project, "exec", "-T", "postgres", "psql", "-U",
                                    "postgres", "-d", "opspilot", "-At", "-c", sql).strip())
    expected = ["REST", "SUPERVISOR", "A2A", "AGENT_RUNTIME", "TOOL", "PROVIDER",
                "ARTIFACT", "EVIDENCE", "ANALYSIS_SEAL", "RCA", "SSE"]
    ordered = []
    parent = None
    while len(ordered) < len(graph_rows):
        matches = [row for row in graph_rows if row["parent_audit_id"] == parent]
        if len(matches) != 1:
            raise RuntimeError("correlation graph is not a single parent-authorized chain")
        ordered.append(matches[0])
        parent = matches[0]["audit_id"]
    graph_rows = ordered
    boundaries = [row["boundary"] for row in graph_rows]
    if boundaries != expected:
        raise RuntimeError(f"correlation graph incomplete: {boundaries}")
    provider_node = next(row for row in graph_rows if row["boundary"] == "PROVIDER")
    if "provider=deepseek" not in provider_node["summary"]:
        raise RuntimeError("provider boundary was not backed by the configured provider")

    store_sql = f"""SELECT json_build_object(
        'toolCalls',(SELECT count(*) FROM opspilot.tool_call
          WHERE run_id='{run_id}' AND request_id='{request_id}' AND trace_id='{trace_id}'
            AND step_id='{step_id}' AND a2a_task_id='{a2a['taskId']}'
            AND invocation_id='{invocation_id}'),
        'modelCalls',(SELECT count(*) FROM opspilot.model_call
          WHERE run_id='{run_id}' AND request_id='{request_id}' AND trace_id='{trace_id}'
            AND step_id='{step_id}' AND a2a_task_id='{a2a['taskId']}'
            AND invocation_id='{invocation_id}'),
        'outboxEvents',(SELECT count(*) FROM opspilot.outbox_event
          WHERE run_id='{run_id}' AND request_id='{request_id}' AND trace_id='{trace_id}'
            AND step_id='{step_id}' AND a2a_task_id='{a2a['taskId']}'
            AND invocation_id='{invocation_id}'))::text;"""
    correlated_stores = json.loads(compose(
        args.project, "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "opspilot",
        "-At", "-c", store_sql).strip())
    if correlated_stores != {"toolCalls": 1, "modelCalls": 1, "outboxEvents": 1}:
        raise RuntimeError(f"correlation fields missing from boundary stores: {correlated_stores}")
    edges = [{"from": graph_rows[index - 1]["audit_id"], "to": row["audit_id"]}
             for index, row in enumerate(graph_rows) if index]
    graph = {"schemaVersion": "1.0.0", "project": args.project,
             "principalId": principal, "incidentId": incident_id, "runId": run_id,
             "requestId": request_id, "traceId": trace_id,
             "correlatedStores": correlated_stores, "nodes": graph_rows, "edges": edges}

    ps_lines = [json.loads(line) for line in compose(args.project, "ps", "--format", "json").splitlines()
                if line.strip()]
    ports = {item["Service"]: {"state": item["State"], "health": item.get("Health", ""),
                               "publishers": item.get("Publishers", [])} for item in ps_lines}
    required_processes = {"opspilot-server", "evidence-agent", "code-agent", "knowledge-agent",
                          "diagnosis-agent", "remediation-agent"}
    if not required_processes.issubset(ports) or any(
            ports[name]["state"] != "running" for name in required_processes):
        raise RuntimeError("not all six application processes are running")

    (output / "http-packet-capture.json").write_text(
        json.dumps({"schemaVersion": "1.0.0", "packets": packets}, indent=2), encoding="utf-8")
    (output / "correlation-graph.json").write_text(json.dumps(graph, indent=2), encoding="utf-8")
    (output / "compose-process-ports.json").write_text(json.dumps(ports, indent=2), encoding="utf-8")
    (output / "compose-vertical.log").write_text(
        compose(args.project, "logs", "--no-color", "opspilot-server", "evidence-agent"),
        encoding="utf-8")
    print(json.dumps({"status": "PASS", "nodes": len(graph_rows),
                      "processes": len(required_processes), "runId": run_id}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
