#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p evidence
compose=(docker compose -f lab/compose.yaml)
app_pid=
cleanup() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  "${compose[@]}" logs --no-color > evidence/containers.log 2>&1 || true
  "${compose[@]}" ps -a > evidence/containers-status.txt || true
  "${compose[@]}" down -v --remove-orphans || true
}
trap cleanup EXIT
java -version 2> evidence/java-version.txt
"${compose[@]}" config --images > evidence/images.txt
"${compose[@]}" up -d
topic_ready=0
for ((attempt=0;attempt<90;attempt++)); do
  "${compose[@]}" exec -T broker sh -c 'sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:10911 -t otel-book-orders' > evidence/topic.txt 2>&1 || true
  if grep -qi 'create topic.*success' evidence/topic.txt; then
    "${compose[@]}" exec -T broker sh -c 'sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin topicRoute -n namesrv:9876 -t otel-book-orders' > evidence/topic-route.json 2>&1 || true
    if grep -q 'brokerAddrs' evidence/topic-route.json; then topic_ready=1; break; fi
  fi
  sleep 2
done
[[ "$topic_ready" == 1 ]] || { cat evidence/topic.txt; exit 1; }
"${compose[@]}" exec -T redis redis-cli PING > evidence/redis-ping.txt
java -jar lab/target/observability-lab.jar > evidence/application.log 2>&1 &
app_pid=$!
python3 tests/verify_system.py --phase full
"${compose[@]}" logs --no-color collector > evidence/collector.log
python3 - <<'PY'
import json
from pathlib import Path
results=json.loads(Path('evidence/results.json').read_text())
trace=results['checks']['normal']['response']['trace_id']
logs=Path('evidence/collector.log').read_text().lower()
assert trace.lower() in logs, 'Collector did not receive trace-correlated OTLP logs'
print('PASS actual OTLP logs contain the same trace id as Tempo spans')
PY
kill "$app_pid"
wait "$app_pid" || true
app_pid=
SAMPLER_RATIO=0 java -jar lab/target/observability-lab.jar > evidence/application-sampling-zero.log 2>&1 &
app_pid=$!
python3 tests/verify_system.py --phase sampled-out
"${compose[@]}" exec -T redis redis-cli --scan --pattern 'otelbook:*' > evidence/redis-keys.txt
