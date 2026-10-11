#!/usr/bin/env bash
# Run as root only on the dedicated Azure demo VM via Azure Run Command.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq podman openssl
if podman container exists oracle-demo-db; then
  echo 'Database container already exists; refusing to replace it.'
  exit 0
fi
if ! podman secret inspect oracle_pwd >/dev/null 2>&1; then
  openssl rand -base64 36 | podman secret create oracle_pwd - >/dev/null
fi
podman pull container-registry.oracle.com/database/free:latest
# Pin this installation to the digest actually pulled, not a mutable runtime tag.
db_image=$(podman image inspect container-registry.oracle.com/database/free:latest --format '{{index .RepoDigests 0}}')
podman volume create oracle-demo-data >/dev/null
podman run -d --name oracle-demo-db --secret=oracle_pwd \
  --shm-size=1g -p 127.0.0.1:1521:1521 \
  -v oracle-demo-data:/opt/oracle/oradata "$db_image"
cd /etc/systemd/system
podman generate systemd --name oracle-demo-db --files
systemctl daemon-reload
systemctl enable container-oracle-demo-db.service
echo "Database image: $db_image"
echo 'Startup initiated; check database health before use. No public listener.'
