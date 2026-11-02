#!/usr/bin/env bash
# =============================================================================
# 将 containerd 数据目录迁移到非系统盘（镜像层实际落盘位置）
#     pkexec bash deploy/local/fix-containerd-root.sh     （或 sudo bash ...）
#
# 背景：Docker 29 默认启用 containerd image store，镜像层存储在
#       /var/lib/containerd（系统盘），仅 daemon.json 的 data-root 不够。
# 本脚本：containerd root -> ${DATA_ROOT:-/data}/containerd，
#        并清理 apt/journal 缓存（系统盘减负）。
# 影响：容器将停止，镜像需重新拉取（约 3.4GB）；
#       两个数据库的数据卷在 ${DATA_ROOT:-/data}/docker-volumes 不受影响。
# =============================================================================
set -euo pipefail

DATA_ROOT=${DATA_ROOT:-/data}
CS_ROOT=$DATA_ROOT/containerd

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 运行： pkexec bash $0 或 sudo bash $0" >&2
  exit 1
fi

echo "==> [1/4] 停止 docker / containerd"
systemctl stop docker 2>/dev/null || true
systemctl stop containerd 2>/dev/null || true

echo "==> [2/4] 配置 containerd root -> ${CS_ROOT}"
mkdir -p "${CS_ROOT}" /etc/containerd
containerd config default > /etc/containerd/config.toml
sed -i -E "s|^root = .*|root = \"${CS_ROOT}\"|" /etc/containerd/config.toml
grep -E "^(root|state) =" /etc/containerd/config.toml

echo "==> [3/4] 清理旧数据与缓存（系统盘减负）"
echo "--- 清理前 ---"
du -sh /var/lib/containerd /var/cache/apt /var/log 2>/dev/null || true
rm -rf /var/lib/containerd/*
apt-get clean
journalctl --vacuum-size=200M >/dev/null 2>&1 || true
echo "--- 清理后 ---"
du -sh /var/lib/containerd /var/cache/apt 2>/dev/null || true

echo "==> [4/4] 启动服务并验证"
systemctl start containerd
sleep 2
systemctl start docker
sleep 3
echo "containerd: $(systemctl is-active containerd) | docker: $(systemctl is-active docker)"
docker info --format 'DockerRootDir : {{.DockerRootDir}}'
du -sh "${CS_ROOT}" 2>/dev/null || true

echo ""
echo "===== 完成。镜像需重新拉取，请回到 IDE 继续。 ====="
df -h / "${DATA_ROOT}" | tail -2
