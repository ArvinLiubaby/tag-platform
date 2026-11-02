#!/usr/bin/env bash
# ============================================================================
# 测试环境启动前置检查：等待 MySQL(3306) 与 StarRocks FE+BE 就绪
#
# 仅探测 TCP 端口不够：StarRocks FE 监听 9030 后仍需数秒才能接受查询，
# 应用过早启动会报 "Communications link failure" 并触发一次失败重启。
# 这里额外要求 FE 的 /api/health 返回 status=OK 且 online_backend_num>=1。
# ============================================================================
set -u

wait_port() { # <port> <desc>
  local port="$1" desc="$2"
  for i in $(seq 1 60); do
    if timeout 1 bash -c "</dev/tcp/127.0.0.1/${port}" 2>/dev/null; then
      echo "${desc} (${port}) ready after $((i * 5))s"
      return 0
    fi
    sleep 5
  done
  echo "${desc} (${port}) not ready after 300s" >&2
  return 1
}

wait_starrocks() {
  local i
  for i in $(seq 1 60); do
    if curl -sf -m 3 http://127.0.0.1:8030/api/health 2>/dev/null | python3 -c '
import sys, json
d = json.load(sys.stdin)
sys.exit(0 if d.get("status") == "OK" and d.get("online_backend_num", 0) >= 1 else 1)
' 2>/dev/null; then
      echo "StarRocks FE/BE ready after $((i * 5))s"
      return 0
    fi
    sleep 5
  done
  echo "StarRocks FE/BE not ready after 300s" >&2
  return 1
}

wait_port 3306 "MySQL"
wait_starrocks
