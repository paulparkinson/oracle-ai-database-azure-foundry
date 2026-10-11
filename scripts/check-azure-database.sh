#!/usr/bin/env bash
# Read-only checks; run as root on the dedicated Azure VM.
set -euo pipefail
podman inspect oracle-demo-db --format 'Container={{.State.Status}} Image={{.ImageName}}'
podman port oracle-demo-db
podman exec -i oracle-demo-db sqlplus -s '/ as sysdba' <<'SQL'
whenever sqlerror exit failure
set pages 100 lines 180
select banner_full from v$version;
select name, open_mode from v$pdbs;
alter session set container=FREEPDB1;
select vector_distance(to_vector('[1,0,0]'), to_vector('[1,0,0]'), COSINE) as vector_distance from dual;
exit
SQL
