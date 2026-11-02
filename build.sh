#!/usr/bin/env bash
# 标签画像平台 — 构建入口
# 用法：./build.sh clean package          （构建）
#       ./build.sh -pl server test         （仅测 server）
#       ./build.sh spring-boot:run -pl server -Dspring-boot.run.profiles=demo   （demo 运行）
set -euo pipefail
cd "$(dirname "$0")"

# ---------- 1. 探测 JDK（优先 JDK 21；JAVA_HOME 显式指定优先，否则按候选顺序扫描） ----------
detect_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/javac" ]; then echo "${JAVA_HOME}"; return; fi
  local candidates=(
    "$HOME/.sdkman/candidates/java/current"                              # SDKMAN 当前 JDK（Temurin 21 LTS）
    "$HOME/.sdkman/candidates/java/21"*
    "$HOME/jdk21"*
    "$HOME/.jdks/"*
    "$HOME/.jdks/"*
    "$HOME/jdk"*
    /usr/lib/jvm/*
    "/opt/java"*
  )
  local c
  for c in "${candidates[@]}"; do
    if [ -x "${c}/bin/javac" ]; then echo "${c}"; return; fi
  done
  echo ""
}

JDK="$(detect_jdk)"
if [ -z "${JDK}" ]; then
  echo "[build.sh] 未找到 JDK（需要 17+）。请安装后设置 JAVA_HOME 再重试。" >&2
  exit 1
fi
export JAVA_HOME="${JDK}"
echo "[build.sh] JAVA_HOME=${JAVA_HOME}"

# ---------- 2. 探测 Maven（PATH 优先；否则使用本机便携 Maven） ----------
MVN_CMD="mvn"
if ! command -v mvn >/dev/null 2>&1; then
  if [ -x "$HOME/apache-maven-3.9.12/bin/mvn" ]; then
    MVN_CMD="$HOME/apache-maven-3.9.12/bin/mvn"
  else
    echo "[build.sh] 未找到 Maven，请安装 maven 或设置 MVN 环境变量。" >&2
    exit 1
  fi
fi
MVN_CMD="${MVN:-$MVN_CMD}"
echo "[build.sh] MVN=${MVN_CMD}"

# ---------- 3. 执行（项目 settings.xml：阿里云镜像 + 显式本地仓库，避免全局 settings 残留 Windows 路径） ----------
exec "${MVN_CMD}" -s "$(pwd)/settings.xml" -Dmaven.repo.local="${HOME}/.m2/repository" "$@"
