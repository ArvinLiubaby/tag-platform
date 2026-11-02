#!/usr/bin/env bash
# =============================================================================
# Docker/containerd 数据落位改造：NTFS 数据盘上的 ext4 loop 镜像
#     pkexec bash deploy/local/setup-docker-loop.sh     （或 sudo bash ...）
#
# 背景：overlayfs 不能以 fuse(NTFS) 为 backing（docker run 报 invalid argument），
#       镜像层/快照必须位于 ext4 等原生文件系统。本脚本在 NTFS 数据盘上创建
#       ext4 loop 镜像（40G 稀疏，随用随占），containerd root 与 docker
#       data-root 均迁入其中，并安装开机自动挂载单元（等待数据盘登录后挂载）。
# 影响：容器停止、镜像重拉（约 3.4GB）；数据库数据卷 docker-volumes 不影响。
# =============================================================================
set -euo pipefail

DATA_ROOT=${DATA_ROOT:-/data}
IMG=$DATA_ROOT/docker-data.img
MNT=$DATA_ROOT/docker-data
SIZE=40G

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行： pkexec bash $0" >&2; exit 1; }

echo "==> [1/8] 停止 docker / containerd"
systemctl stop docker 2>/dev/null || true
systemctl stop containerd 2>/dev/null || true

echo "==> [2/8] 创建 ext4 loop 镜像（稀疏 $SIZE，实际按使用增长）"
if [ ! -f "$IMG" ]; then
  truncate -s $SIZE "$IMG"
  mkfs.ext4 -q -m 0 "$IMG"
fi
ls -lh "$IMG"

echo "==> [3/8] 挂载到 $MNT"
mkdir -p "$MNT"
mountpoint -q "$MNT" || mount -o loop "$IMG" "$MNT"
findmnt "$MNT" | tail -1

echo "==> [4/8] 创建数据目录"
mkdir -p "$MNT/containerd" "$MNT/docker"

echo "==> [5/8] 更新配置：containerd root 与 docker data-root"
if [ ! -f /etc/containerd/config.toml ]; then
  containerd config default > /etc/containerd/config.toml
fi
sed -i -E "s|^root = .*|root = \"$MNT/containerd\"|" /etc/containerd/config.toml
grep -E "^(root|state) =" /etc/containerd/config.toml
cat > /etc/docker/daemon.json <<EOF
{
  "data-root": "$MNT/docker",
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockers.xuanyuan.me"
  ],
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "3" }
}
EOF
echo "daemon.json 已更新"

echo "==> [6/8] 安装开机自动挂载单元（等待数据盘出现）"
cat > /usr/local/bin/mount-docker-loop.sh <<'EOS'
#!/bin/bash
# 等待 NTFS 数据盘出现（udisks2 在用户登录后自动挂载），再挂载 ext4 loop 镜像
IMG=$DATA_ROOT/docker-data.img
MNT=$DATA_ROOT/docker-data
for i in $(seq 1 150); do
  [ -e "$IMG" ] && break
  sleep 2
done
[ -e "$IMG" ] || { echo "data disk not mounted yet"; exit 1; }
mkdir -p "$MNT"
mountpoint -q "$MNT" || mount -o loop "$IMG" "$MNT"
systemctl start containerd.service docker.service 2>/dev/null || true
EOS
chmod +x /usr/local/bin/mount-docker-loop.sh

cat > /etc/systemd/system/docker-loop.service <<'EOS'
[Unit]
Description=Mount docker/containerd loop image on data disk (NTFS)
After=local-fs.target
Before=containerd.service docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
TimeoutStartSec=infinity
ExecStart=/usr/local/bin/mount-docker-loop.sh
Restart=on-failure
RestartSec=15

[Install]
WantedBy=multi-user.target
EOS

mkdir -p /etc/systemd/system/containerd.service.d
cat > /etc/systemd/system/containerd.service.d/loop.conf <<'EOS'
[Unit]
After=docker-loop.service
EOS

systemctl daemon-reload
systemctl enable docker-loop.service

echo "==> [7/8] 清理旧数据目录（NTFS 上的旧 root，镜像将重拉）"
rm -rf "$DATA_ROOT/docker" "$DATA_ROOT/containerd"

echo "==> [8/8] 启动服务并验证"
systemctl start docker-loop.service
systemctl start containerd
sleep 2
systemctl start docker
sleep 3
echo "docker-loop: $(systemctl is-active docker-loop) | containerd: $(systemctl is-active containerd) | docker: $(systemctl is-active docker)"
docker info --format 'DockerRootDir : {{.DockerRootDir}}'
findmnt "$MNT" | tail -1
echo "--- 磁盘 ---"
df -h "$MNT" / | tail -2
echo ""
echo "===== 完成。镜像需重新拉取，请回到 IDE 继续。 ====="
