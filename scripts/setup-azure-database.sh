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
# Oracle AI Database 26ai Free 23.26.3.0.0, verified on the Azure demo VM.
db_image=container-registry.oracle.com/database/free@sha256:c4d9b15a1b2240ce90730c23ce298fcdd34f6793792c1b3849181a080009c322
podman pull "$db_image"
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
