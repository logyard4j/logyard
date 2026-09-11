from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def request(url: str, method: str = "GET", body=None):
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    content_type = "application/x-ndjson" if isinstance(body, bytes) else "application/json"
    query = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": content_type})
    try:
        with urllib.request.urlopen(query, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        raise RuntimeError(f"{method} {url}: {failure.code}: {failure.read().decode()[:6000]}") from failure


def docker(*arguments: str) -> str:
    result = subprocess.run(["docker", *arguments], text=True, capture_output=True, timeout=180)
    require(result.returncode == 0, f"docker {' '.join(arguments)} failed: {result.stderr[-6000:]}")
    return result.stdout.strip()


@contextmanager
def server(configuration: dict, target: Path):
    supplied = os.environ.get("LOGYARD_ECS_URL")
    container = None
    try:
        if supplied:
            parsed = urllib.parse.urlsplit(supplied)
            require(parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
                    and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
                    and parsed.path in {"", "/"}, "LOGYARD_ECS_URL must be an unauthenticated loopback HTTP origin")
            endpoint = supplied.rstrip("/")
        else:
            container = docker("run", "--detach", "--memory", "1g", "--cpus", "2",
                               "--publish", "127.0.0.1::9200", "--env", "discovery.type=single-node",
                               "--env", "xpack.security.enabled=false", "--env", "xpack.ml.enabled=false",
                               "--env", "ES_JAVA_OPTS=-Xms512m -Xmx512m", configuration["image"])
            port = docker("port", container, "9200/tcp").rsplit(":", 1)[1]
            endpoint = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 90
        while True:
            try:
                info = request(endpoint)
                break
            except (OSError, RuntimeError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
        require(info["version"]["number"] == configuration["elasticsearch"], "Elasticsearch version differs from fixture.toml")
        health = request(f"{endpoint}/_cluster/health?wait_for_status=yellow&timeout=30s")
        require(not health["timed_out"], "Elasticsearch did not become ready")
        yield endpoint
    finally:
        if container:
            try:
                (target / "elasticsearch.log").write_text(docker("logs", container))
            finally:
                docker("rm", "--force", container)


def verify(endpoint: str, root: Path, target: Path, configuration: dict) -> dict:
    index = f"logyard-ecs-canary-{uuid.uuid4().hex}"
    mapping = json.loads((root / "tests/ecs/mapping.json").read_text())
    require(mapping["mappings"]["_meta"]["ecs_version"] == configuration["ecs"], "ECS mapping version mismatch")
    created = False
    try:
        result = request(f"{endpoint}/{index}", "PUT", mapping)
        created = True
        require(result.get("acknowledged"), "Index creation was not acknowledged")
        records = {name: json.loads((target / f"{name}.json").read_text())
                   for name in ("base", "exception", "excluded", "truncated")}
        for record in records.values():
            require(record.get("ecs.version") == configuration["ecs"], "Encoded ECS version mismatch")
        payload = "".join(json.dumps({"create": {"_id": name}}) + "\n" + json.dumps(record) + "\n"
                          for name, record in records.items()).encode()
        indexed = request(f"{endpoint}/{index}/_bulk?refresh=true", "POST", payload)
        require(not indexed.get("errors"), f"ECS mapping rejected encoder output: {json.dumps(indexed)}")
        require(len(indexed["items"]) == 4 and all(item["create"]["status"] == 201 for item in indexed["items"]),
                "Expected four newly indexed fixture records")

        service_terms = {"service.name": "logyard-canary", "service.environment": "test",
                         "service.version": "canary-version", "service.node.name": "node-1"}
        service = search(endpoint, index, {"bool": {"filter": [{"term": {key: value}} for key, value in service_terms.items()]}})
        require(service == {"base", "exception"}, f"Standard ECS service queries returned {service}")
        correlated = search(endpoint, index, {"bool": {"filter": [
            {"term": {"trace.id": "0123456789abcdef0123456789abcdef"}},
            {"term": {"span.id": "0123456789abcdef"}},
            {"term": {"process.thread.id": 7}}, {"term": {"process.thread.name": "worker"}},
            {"term": {"labels.count": "7"}}, {"term": {"labels.paid": "true"}}
        ]}})
        require(correlated == {"base", "exception"}, "Trace, thread, or keyword label queries did not match")
        require(search(endpoint, index, {"exists": {"field": "error.stack_trace"}}) == {"exception"},
                "ECS error.stack_trace was not indexed")
        require(search(endpoint, index, {"term": {"logyard.output.truncated": True}}) == {"truncated"},
                "Truncation marker was not indexed")
        labels = records["base"]["labels"]
        require(all(isinstance(value, str) for value in labels.values()), "ECS labels must be scalar keyword strings")
        require(json.loads(labels["nested"]) == {"secret": "[REDACTED]"}, "Structured label changed its captured value")
        trace = records["exception"]["error"]["stack_trace"]
        require(isinstance(trace, str) and "Caused by:" in trace and "Suppressed:" in trace, "Incomplete ECS exception text")
        excluded = records["excluded"]
        for key in ("message", "logyard.message_template", "log.logger", "service", "logyard.resource", "labels", "trace.id", "span.id", "error"):
            require(key not in excluded, f"Excluded field reappeared: {key}")
        require("service" not in records["truncated"] and "labels" not in records["truncated"], "Fixture did not reach bounded fallback")
        return {"passed": True, "ecs": configuration["ecs"], "elasticsearch": configuration["elasticsearch"],
                "indexed_records": 4, "service_query_matches": 2, "correlated_query_matches": 2,
                "ingest_pipeline": None, "mapping": "tests/ecs/mapping.json"}
    finally:
        if created:
            request(f"{endpoint}/{index}", "DELETE")


def search(endpoint: str, index: str, query: dict) -> set[str]:
    result = request(f"{endpoint}/{index}/_search", "POST", {"query": query, "size": 10, "track_total_hits": True})
    require(not result.get("timed_out") and result["_shards"]["failed"] == 0, "Elasticsearch query did not complete")
    return {hit["_id"] for hit in result["hits"]["hits"]}


def main() -> None:
    root = Path(sys.argv[1]).resolve()
    target = (root / "target/ecs-verify").resolve()
    configuration = tomllib.loads((root / "tests/ecs/fixture.toml").read_text())
    with server(configuration, target) as endpoint:
        result = verify(endpoint, root, target, configuration)
    (target / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
