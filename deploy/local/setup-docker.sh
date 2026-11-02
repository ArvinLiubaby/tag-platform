#!/usr/bin/env bash
# =============================================================================
# 本机 Docker 安装与配置（需要一次 sudo）：
#     sudo bash deploy/local/setup-docker.sh
#
# 内容：
#   1. apt 安装 docker.io（Ubuntu 仓库版，含 containerd/runc）
#   2. Docker 数据目录迁移到非系统盘：${DATA_ROOT:-/data/docker}
#   3. 配置国内镜像加速（Docker Hub 直连较慢）
#   4. 启动服务 + 开机自启 + 当前用户加入 docker 组（免 sudo）
# =============================================================================
set -euo pipefail

DATA_ROOT=${DATA_ROOT:-/data/docker}

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 sudo 运行： sudo bash $0" >&2
  exit 1
fi

echo "==> [1/5] apt 安装 docker.io"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq docker.io

echo "==> [2/5] 创建非系统盘数据目录: ${DATA_ROOT}"
mkdir -p "${DATA_ROOT}"

echo "==> [3/5] 写入 /etc/docker/daemon.json（data-root + 镜像加速 + 日志轮转）"
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<EOF
{
  "data-root": "${DATA_ROOT}",
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockers.xuanyuan.me"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF

echo "==> [4/5] 启动 Docker 并设置开机自启"
systemctl daemon-reload
systemctl enable --now docker
systemctl restart docker

echo "==> [5/5] 当前用户加入 docker 组（免 sudo）"
TARGET_USER="${SUDO_USER:-arvin}"
usermod -aG docker "${TARGET_USER}"

echo ""
echo "=========================== 安装完成 ==========================="
docker --version
docker info --format 'DockerRootDir : {{.DockerRootDir}}'
echo "磁盘空间："
df -h "${DATA_ROOT}" | tail -1
echo ""
echo "提示：docker 组权限需新开终端（或 newgrp docker）后生效。"
