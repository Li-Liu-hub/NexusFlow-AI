#!/bin/bash
# R3: 远端应用切换准备（不启动，只改配置）
#   1. 恢复 interface 的 jar（本次不动它）
#   2. 凭据文件补 3 个别名键（新 yml 需要的变量名）
#   3. 重写 backend/gateway/agent 的 systemd unit（JDK21 + 端口 + 内存）
#   4. 应用网关登记 SQL（61 幂等 / 72 / 74）
set -uo pipefail
CRED=/opt/agent-platform-secure/credentials.env
. "$CRED"

echo "=== [1/5] 恢复 interface jar ==="
if [ -f /opt/app/interface/app.jar.20261001.bak ] && [ ! -f /opt/app/interface/app.jar ]; then
  mv /opt/app/interface/app.jar.20261001.bak /opt/app/interface/app.jar
  echo "  已恢复（interface 本次不变更）"
else
  echo "  跳过"
fi

echo "=== [2/5] 凭据文件补别名键 ==="
# 新 yml 用 ${PG_PASSWORD} / ${MYSQL_USER} / ${MYSQL_PASSWORD}，
# 而凭据文件里叫 PG_PW / MYSQL_APP_USER / MYSQL_APP_PW —— 补上别名，避免改 yml
add_if_missing() {
  local key="$1" val="$2"
  if ! grep -q "^${key}=" "$CRED"; then
    printf '%s=%s\n' "$key" "$val" >> "$CRED"
    echo "  + ${key}"
  else
    echo "  = ${key}（已存在，跳过）"
  fi
}
add_if_missing PG_PASSWORD "$PG_PW"
add_if_missing MYSQL_USER "$MYSQL_APP_USER"
add_if_missing MYSQL_PASSWORD "$MYSQL_APP_PW"
chmod 600 "$CRED"

echo "=== [3/5] 重写 systemd unit（backend/gateway/agent） ==="
write_unit() {
  local name="$1" workdir="$2" java_bin="$3" xmx="$4" port="$5"
  cat > "/etc/systemd/system/${name}.service" <<EOF
[Unit]
Description=${name}
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=${workdir}
EnvironmentFile=${CRED}
ExecStart=${java_bin} -Xmx${xmx} -XX:+UseG1GC -Dfile.encoding=UTF-8 -jar ${workdir}/app.jar --server.port=${port}
SuccessExitStatus=143
Restart=on-failure
RestartSec=15
StandardOutput=append:/var/log/aismp/${name}.log
StandardError=append:/var/log/aismp/${name}.log

[Install]
WantedBy=multi-user.target
EOF
  echo "  ${name}: JDK=? -Xmx${xmx} port=${port}"
}

# 2026-10-06 起 backend/gateway 升到 Spring Boot 4.0.7 → 必须 JDK21（原来是 JDK8）
write_unit aismp-backend /opt/app/backend /usr/lib/jvm/java-21-openjdk-amd64/bin/java 300m 7529
write_unit aismp-gateway /opt/app/gateway /usr/lib/jvm/java-21-openjdk-amd64/bin/java 300m 8090
write_unit aismp-agent   /opt/app/agent   /usr/lib/jvm/java-21-openjdk-amd64/bin/java 448m 8124

systemctl daemon-reload

echo "=== [4/5] 应用网关登记 SQL ==="
for f in /tmp/_deploy_61-digital-human-interface-registry.sql /tmp/_deploy_72-terminal-route-registry.sql /tmp/_deploy_74-portal-route-registry.sql; do
  if [ -f "$f" ]; then
    echo "  --- $(basename $f) ---"
    mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" < "$f" 2>/dev/null | tail -3
  else
    echo "  !! 缺少 $f"
  fi
done

echo "=== [5/5] 当前 interface_info 关键登记 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT method, url FROM api_db.interface_info WHERE isDelete=0 AND (url LIKE '/api/terminal%' OR url LIKE '/api/transferOrder%' OR url LIKE '/api/agent%' OR url LIKE '/api/admin%' OR url LIKE '/digitalHuman%') ORDER BY url;" 2>/dev/null

echo "R3-PREP-DONE"