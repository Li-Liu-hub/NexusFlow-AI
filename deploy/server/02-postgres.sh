#!/bin/bash
# 02-postgres.sh — PostgreSQL 16 + pgvector, remote access, app role/db
set -uo pipefail
echo "########## [POSTGRESQL] ##########"

PGVER=18
PGCONF=/etc/postgresql/${PGVER}/main/postgresql.conf
PGHBA=/etc/postgresql/${PGVER}/main/pg_hba.conf
APPUSER=my_user
APPPASS=123456
APPMAINDB=terminal_kb

[ -f "${PGCONF}.orig" ] || cp "${PGCONF}" "${PGCONF}.orig"
[ -f "${PGHBA}.orig" ]  || cp "${PGHBA}"  "${PGHBA}.orig"

# --- 1) listen on all interfaces ---
if grep -qE "^[[:space:]]*#?[[:space:]]*listen_addresses" "${PGCONF}"; then
  sed -i -E "s|^[[:space:]]*#?[[:space:]]*listen_addresses[[:space:]]*=.*|listen_addresses = '*'|" "${PGCONF}"
else
  printf "\nlisten_addresses = '*'\n" >> "${PGCONF}"
fi
sed -i -E "s|^[[:space:]]*#?[[:space:]]*password_encryption[[:space:]]*=.*|password_encryption = scram-sha-256|" "${PGCONF}"

echo "--- postgresql.conf ---"
grep -nE "^[[:space:]]*(listen_addresses|password_encryption)" "${PGCONF}"

# --- 2) pg_hba rules for remote ---
if ! grep -q "0.0.0.0/0" "${PGHBA}"; then
  cat >> "${PGHBA}" <<'EOF'

# ==== remote access (added by deploy) ====
host    all             all             0.0.0.0/0               scram-sha-256
host    all             all             ::/0                    scram-sha-256
EOF
fi
echo "--- pg_hba tail ---"
tail -4 "${PGHBA}"

systemctl restart postgresql
sleep 3
echo -n "postgresql active: "; systemctl is-active postgresql

# --- 3) role + database + extension ---
if [ "$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${APPUSER}'")" = "1" ]; then
  sudo -u postgres psql -qc "ALTER ROLE ${APPUSER} WITH LOGIN PASSWORD '${APPPASS}';"
else
  sudo -u postgres psql -qc "CREATE ROLE ${APPUSER} WITH LOGIN PASSWORD '${APPPASS}';"
fi

if [ "$(sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${APPMAINDB}'")" != "1" ]; then
  sudo -u postgres createdb -O "${APPUSER}" "${APPMAINDB}"
fi

sudo -u postgres psql -qc "GRANT ALL PRIVILEGES ON DATABASE ${APPMAINDB} TO ${APPUSER};"
sudo -u postgres psql -d "${APPMAINDB}" -qc "CREATE EXTENSION IF NOT EXISTS vector;"
sudo -u postgres psql -d "${APPMAINDB}" -qc "ALTER SCHEMA public OWNER TO ${APPUSER};"
sudo -u postgres psql -d "${APPMAINDB}" -qc "GRANT ALL ON SCHEMA public TO ${APPUSER};"

echo "--- roles / databases ---"
sudo -u postgres psql -tAc "SELECT rolname||' canlogin='||rolcanlogin FROM pg_roles WHERE rolname IN ('postgres','${APPUSER}');"
sudo -u postgres psql -tAc "SELECT datname FROM pg_database WHERE datistemplate=false;"
echo -n "vector ext: "
sudo -u postgres psql -d "${APPMAINDB}" -tAc "SELECT extname||' '||extversion FROM pg_extension WHERE extname='vector';"

# --- 4) smoke test over TCP as the app user ---
echo "--- TCP login as ${APPUSER} ---"
PGPASSWORD="${APPPASS}" psql -h 127.0.0.1 -p 5432 -U "${APPUSER}" -d "${APPMAINDB}" -tAc \
  "SELECT current_user||' @ '||current_database()||' @ '||inet_server_addr();"

echo "--- vector smoke test (1024 dims) ---"
PGPASSWORD="${APPPASS}" psql -h 127.0.0.1 -p 5432 -U "${APPUSER}" -d "${APPMAINDB}" -v ON_ERROR_STOP=1 <<'EOSQL'
DROP TABLE IF EXISTS _vector_smoke_test;
CREATE TABLE _vector_smoke_test (id bigserial primary key, embedding vector(1024));
INSERT INTO _vector_smoke_test (embedding)
SELECT (SELECT array_agg(random()) FROM generate_series(1,1024))::vector;
SELECT 'rows=' || count(*) AS r FROM _vector_smoke_test;
SELECT 'dims=' || vector_dims(embedding) AS d FROM _vector_smoke_test LIMIT 1;
DROP TABLE _vector_smoke_test;
EOSQL

echo "--- app user can run CREATE EXTENSION IF NOT EXISTS vector? ---"
PGPASSWORD="${APPPASS}" psql -h 127.0.0.1 -U "${APPUSER}" -d "${APPMAINDB}" \
  -c "CREATE EXTENSION IF NOT EXISTS vector;" 2>&1 | head -3

echo "POSTGRES-DONE"
