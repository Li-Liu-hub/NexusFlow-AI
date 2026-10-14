#!/bin/bash
# 06-nacos.sh — Nacos 2.4.3 standalone on :8848, auth disabled (matches project config)
set -uo pipefail
echo "########## [NACOS] ##########"

VER=2.4.3
DEST=/opt/nacos

if [ ! -f "${DEST}/target/nacos-server.jar" ]; then
  mkdir -p /opt/_dl && cd /opt/_dl
  URLS=(
    "https://gh-proxy.com/https://github.com/alibaba/nacos/releases/download/${VER}/nacos-server-${VER}.tar.gz"
    "https://ghfast.top/https://github.com/alibaba/nacos/releases/download/${VER}/nacos-server-${VER}.tar.gz"
    "https://ghproxy.net/https://github.com/alibaba/nacos/releases/download/${VER}/nacos-server-${VER}.tar.gz"
  )
  ok=0
  for u in "${URLS[@]}"; do
    echo "try: $u"
    if curl -fL --retry 2 --connect-timeout 15 -m 900 -o nacos.tar.gz "$u"; then
      if tar -tzf nacos.tar.gz >/dev/null 2>&1; then ok=1; echo "OK <- $u"; break; fi
    fi
    rm -f nacos.tar.gz
  done
  [ "${ok}" = "1" ] || { echo "NACOS-DOWNLOAD-FAILED"; exit 1; }
  ls -lh nacos.tar.gz
  rm -rf /opt/nacos
  tar -xzf nacos.tar.gz -C /opt
fi

echo "--- layout ---"
ls /opt/nacos
ls -lh /opt/nacos/target/nacos-server.jar

# resolve JDK 17
# 必须显式指定 17：本机同时装了 JDK 8/17/21，`command -v java` 会按 alternatives
# 解析到版本号最高的 21，而 Nacos 2.4.3 官方只推荐 8/11/17（其依赖的部分库在 21 上不兼容）
JAVA_HOME_DIR=/usr/lib/jvm/java-17-openjdk-amd64
JAVA_BIN="${JAVA_HOME_DIR}/bin/java"
echo "JAVA_BIN=${JAVA_BIN}"
echo "JAVA_HOME=${JAVA_HOME_DIR}"

# --- config ---
APPPROP=/opt/nacos/conf/application.properties
[ -f "${APPPROP}.orig" ] || cp "${APPPROP}" "${APPPROP}.orig"

set_prop() {
  key="$1"; val="$2"; file="$3"
  if grep -qE "^[[:space:]]*#?[[:space:]]*${key}=" "${file}"; then
    sed -i -E "s|^[[:space:]]*#?[[:space:]]*${key}=.*|${key}=${val}|" "${file}"
  else
    printf "\n%s=%s\n" "${key}" "${val}" >> "${file}"
  fi
}
set_prop "server.port" "8848" "${APPPROP}"
set_prop "nacos.core.auth.enabled" "false" "${APPPROP}"

echo "--- key props ---"
grep -nE "^[[:space:]]*(server.port|nacos.core.auth.enabled|spring.datasource.platform|db.num)=" "${APPPROP}"

# --- systemd unit (Type=simple, exec the jar directly) ---
cat > /etc/systemd/system/nacos.service <<EOF
[Unit]
Description=Nacos Server (standalone)
Documentation=https://nacos.io
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
Environment="JAVA_HOME=${JAVA_HOME_DIR}"
WorkingDirectory=/opt/nacos
ExecStart=${JAVA_BIN} -Xms512m -Xmx512m -Xmn256m -XX:+UseG1GC -Dnacos.standalone=true -Dnacos.home=/opt/nacos -jar /opt/nacos/target/nacos-server.jar --spring.config.additional-location=file:/opt/nacos/conf/ --logging.config=/opt/nacos/conf/nacos-logback.xml --server.max-http-header-size=524288
ExecStop=/bin/kill -s TERM \$MAINPID
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable nacos >/dev/null 2>&1
systemctl restart nacos

echo "--- waiting for nacos to come up ---"
for i in $(seq 1 30); do
  sleep 5
  code=$(curl -s -o /dev/null -m 5 -w "%{http_code}" "http://127.0.0.1:8848/nacos/v1/console/health/readiness" || true)
  echo "  t=$((i*5))s http=$code"
  if [ "$code" = "200" ]; then break; fi
done

echo "--- status ---"
systemctl is-active nacos
ss -tlnp 2>/dev/null | grep -E "8848|9848|9849|7848" || echo "(no nacos ports)"

echo "--- readiness ---"
curl -s -m 10 "http://127.0.0.1:8848/nacos/v1/console/health/readiness"; echo
echo -n "console page: "
curl -s -o /dev/null -m 10 -w "%{http_code}\n" "http://127.0.0.1:8848/nacos/index.html"

echo "--- namespace list via open API ---"
curl -s -m 10 "http://127.0.0.1:8848/nacos/v1/console/namespaces" | head -c 400; echo

echo "--- last 25 log lines ---"
tail -25 /opt/nacos/logs/start.out 2>/dev/null || tail -25 /opt/nacos/logs/nacos.log 2>/dev/null || echo "(no log found)"

echo "NACOS-DONE"
