#!/bin/bash
# R2: 远端安装 Nacos 3.2.4（MySQL 存储）
#   1. 下载解压到 /opt/nacos-3.2.4（官方源，避免 GitHub 慢）
#   2. 建 nacos_config 库 + 导入 mysql-schema.sql
#   3. 写 conf/application.properties（mysql 存储；identity/token 用 credentials.env 的值）
#   4. 写新的 systemd unit（仍用 JDK17；3.x 需要 functionMode + auth 开关用 -D 传）
set -euo pipefail
. /opt/agent-platform-secure/credentials.env

NH=/opt/nacos-3.2.4
ZIP=/tmp/nacos-server-3.2.4.zip

echo "=== [1/4] 下载 Nacos 3.2.4 ==="
if [ ! -f "$ZIP" ]; then
  curl -L --noproxy '*' -o "$ZIP" "https://download.nacos.io/nacos-server/nacos-server-3.2.4.zip" 2>/dev/null
fi
ls -lh "$ZIP"

echo "=== 解压到 $NH ==="
if [ ! -d "$NH" ]; then
  rm -rf /tmp/nacos-unzip && mkdir -p /tmp/nacos-unzip
  unzip -q "$ZIP" -d /tmp/nacos-unzip
  mv /tmp/nacos-unzip/nacos "$NH"
fi
ls "$NH" | head -8

echo "=== [2/4] 建 nacos_config 库并导入 schema ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -e "CREATE DATABASE IF NOT EXISTS nacos_config DEFAULT CHARSET utf8mb4;" 2>/dev/null
CNT=$(mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nacos_config';" 2>/dev/null)
echo "  当前表数: $CNT"
if [ "$CNT" -lt 10 ]; then
  mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" nacos_config < "$NH/conf/mysql-schema.sql" 2>/dev/null
  CNT2=$(mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nacos_config';" 2>/dev/null)
  echo "  导入后表数: $CNT2"
fi

echo "=== [3/4] 写 conf/application.properties（mysql 存储） ==="
cat > "$NH/conf/application.properties" <<EOF
#
# Nacos 3.2.4 standalone（2026-10-06 从 2.4.3 升级）
# 存储：外部 MySQL（本机 127.0.0.1:3306/nacos_config），不再用内嵌 Derby
#
server.port=8848
nacos.console.port=8849
nacos.server.contextPath=/nacos
nacos.console.contextPath=

spring.sql.init.platform=mysql
db.num=1
db.url.0=jdbc:mysql://127.0.0.1:3306/nacos_config?characterEncoding=utf8&connectTimeout=1000&socketTimeout=3000&autoReconnect=true&useUnicode=true&useSSL=false&serverTimezone=UTC
db.user=root
db.password=${MYSQL_ROOT_PW}

# identity / token 三元组（SPI 静态实例化，实际生效要靠启动命令 -D 传，见 nacos.service）
nacos.core.auth.system.type=nacos
nacos.core.auth.enabled=false
nacos.core.auth.caching.enabled=true
nacos.core.auth.server.identity.key=${NACOS_IDENTITY_KEY}
nacos.core.auth.server.identity.value=${NACOS_IDENTITY_VALUE}
nacos.core.auth.plugin.nacos.token.secret.key=${NACOS_TOKEN_SECRET}
nacos.core.auth.plugin.nacos.token.expire.seconds=18000

nacos.istio.mcp.server.enabled=false
tomcat.accesslog.enabled=true
EOF
echo "  已写入（密码与 token 不打印）"

echo "=== [4/4] 写 systemd unit ==="
cat > /etc/systemd/system/nacos.service <<'EOF'
[Unit]
Description=Nacos Server 3.2.4 (standalone, MySQL storage)
After=network-online.target mysqld.service
Wants=network-online.target

[Service]
Type=simple
User=root
Environment="JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64"
WorkingDirectory=/opt/nacos-3.2.4
ExecStart=/usr/lib/jvm/java-17-openjdk-amd64/bin/java \
  -Xms384m -Xmx384m -Xmn192m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
  -Dnacos.standalone=true \
  -Dnacos.functionMode=microservice \
  -Dnacos.member.list= \
  -Dnacos.deployment.type=merged \
  -Dnacos.core.auth.enabled=false \
  -Dnacos.core.auth.server.identity.key=__IDK__ \
  -Dnacos.core.auth.server.identity.value=__IDV__ \
  -Dnacos.core.auth.plugin.nacos.token.secret.key=__TOK__ \
  "-Dloader.path=/opt/nacos-3.2.4/plugins,/opt/nacos-3.2.4/plugins/health,/opt/nacos-3.2.4/plugins/cmdb,/opt/nacos-3.2.4/plugins/selector" \
  -Dnacos.home=/opt/nacos-3.2.4 \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -jar /opt/nacos-3.2.4/target/nacos-server.jar \
  --spring.config.additional-location=file:/opt/nacos-3.2.4/conf/ \
  --logging.config=/opt/nacos-3.2.4/conf/nacos-logback.xml \
  --server.max-http-request-header-size=524288

ExecStop=/bin/kill -s TERM $MAINPID
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

# 把三元组的真实值填进 unit（heredoc 用了引号防展开，这里做替换，避免密码出现在命令行）
sed -i "s|__IDK__|${NACOS_IDENTITY_KEY}|; s|__IDV__|${NACOS_IDENTITY_VALUE}|; s|__TOK__|${NACOS_TOKEN_SECRET}|" /etc/systemd/system/nacos.service
chmod 600 /etc/systemd/system/nacos.service
echo "  已写入（unit 含凭据，权限 600）"

echo "R2-NACOS-INSTALL-DONE"