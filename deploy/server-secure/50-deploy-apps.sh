#!/bin/bash
# 50-deploy-apps.sh — 用 systemd 托管四个 Java 服务 + Python 编排层
#
# 内存纪律（2 核 3.6G，中间件已占约 2G）：
#   每个 JVM 都显式限 -Xmx，否则默认按物理内存的 1/4 取堆，四个服务一起起来必 OOM。
#   nginx 托管前端静态产物（不能跑 umi dev，开发模式要 500MB~1GB）。
#
# 前置：jar 已上传到 /opt/app/*/app.jar；编排层代码在 /opt/app/orchestrator
# 用法：python ssh_run_script.py 50-deploy-apps.sh --root
set -uo pipefail

CRED=/opt/agent-platform-secure/credentials.env
J8=/usr/lib/jvm/java-8-openjdk-amd64/bin/java
J21=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
LOG=/var/log/aismp

echo "########## 部署应用 $(date '+%F %T') ##########"
mkdir -p "${LOG}"

[ -x "${J8}" ]  || { echo "!! 找不到 JDK 8：${J8}"; exit 1; }
[ -x "${J21}" ] || { echo "!! 找不到 JDK 21：${J21}"; exit 1; }
[ -f "${CRED}" ] || { echo "!! 找不到凭据：${CRED}"; exit 1; }

# 应用需要的环境变量必须齐全，缺任何一个都会在启动时报错 —— 这里先挡住
for key in MYSQL_APP_USER MYSQL_APP_PW REDIS_PW RABBIT_USER RABBIT_PW PG_PW MIMO_API_KEY SILICONFLOW_API_KEY; do
  if ! grep -q "^${key}=" "${CRED}"; then
    echo "!! 凭据文件缺少 ${key}"
    exit 1
  fi
done
echo "凭据检查通过"

# 凭据文件要被 systemd 读取（root:root 600 即可，服务用 root 还是 ubuntu 都能读）
chmod 600 "${CRED}"

write_unit() {
  local name="$1" workdir="$2" java_bin="$3" xmx="$4" port="$5" extra="$6"
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
ExecStart=${java_bin} -Xmx${xmx} -XX:+UseG1GC -Dfile.encoding=UTF-8 -jar ${workdir}/app.jar ${extra}
SuccessExitStatus=143
Restart=on-failure
RestartSec=15
StandardOutput=append:${LOG}/${name}.log
StandardError=append:${LOG}/${name}.log

[Install]
WantedBy=multi-user.target
EOF
  echo "  写入 /etc/systemd/system/${name}.service（-Xmx${xmx}，端口 ${port}）"
}

echo
echo "--- [1/5] 生成 systemd unit ---"
# 端口通过 server.port 显式传参，避免被环境变量劫持（本机踩过 SERVER__PORT 的坑）
write_unit "aismp-interface" /opt/app/interface "${J8}"  192m 8123 "--server.port=8123"
write_unit "aismp-backend"   /opt/app/backend   "${J8}"  256m 7529 "--server.port=7529"
write_unit "aismp-gateway"   /opt/app/gateway   "${J8}"  256m 8090 "--server.port=8090"
write_unit "aismp-agent"     /opt/app/agent     "${J21}" 512m 8124 "--server.port=8124"

echo
echo "--- [2/5] 编排层（Python）---"
if [ -d /opt/app/orchestrator ]; then
  # 首次：建 venv 装依赖
  if [ ! -x /opt/app/orchestrator/.venv/bin/python ]; then
    echo "  创建 venv 并安装依赖（首次较慢）…"
    apt-get install -y -qq python3-venv python3-pip >/dev/null 2>&1 || true
    python3 -m venv /opt/app/orchestrator/.venv
    /opt/app/orchestrator/.venv/bin/pip install -q --upgrade pip \
      -i https://pypi.tuna.tsinghua.edu.cn/simple
    /opt/app/orchestrator/.venv/bin/pip install -q \
      fastapi uvicorn httpx "langgraph>=0.2" "langchain-openai>=0.2" \
      python-dotenv pydantic-settings \
      -i https://pypi.tuna.tsinghua.edu.cn/simple 2>&1 | tail -3
  fi
  # 编排层自己的 .env（网关三元组 + LLM）
  cat > /opt/app/orchestrator/.env <<EOF
GATEWAY_BASE_URL=http://127.0.0.1:8090
GATEWAY_ACCESS_KEY=aismp-console
GATEWAY_SECRET_KEY=7c1f9a3e5d0b2846af31c7e9b0d45268
LLM_API_KEY=$(grep '^MIMO_API_KEY=' "${CRED}" | cut -d= -f2-)
LLM_BASE_URL=https://api.xiaomimimo.com/v1
LLM_MODEL=mimo-v2.6-flash
LLM_PROXY=
EOF
  cat > /etc/systemd/system/agent-orchestrator.service <<'EOF'
[Unit]
Description=agent-orchestrator
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/app/orchestrator
ExecStart=/opt/app/orchestrator/.venv/bin/python -m uvicorn server:app --host 127.0.0.1 --port 8125
SuccessExitStatus=143
Restart=on-failure
RestartSec=15
StandardOutput=append:/var/log/aismp/orchestrator.log
StandardError=append:/var/log/aismp/orchestrator.log

[Install]
WantedBy=multi-user.target
EOF
  echo "  写入 agent-orchestrator.service"
  chown -R ubuntu:ubuntu /opt/app/orchestrator
else
  echo "  （跳过：/opt/app/orchestrator 不存在，先上传编排层代码）"
fi

echo
echo "--- [3/5] 启动服务（按依赖顺序）---"
systemctl daemon-reload
for svc in aismp-interface aismp-backend aismp-gateway; do
  systemctl enable "${svc}" >/dev/null 2>&1
  systemctl restart "${svc}"
  echo -n "  ${svc}: "
  sleep 1
  systemctl is-active "${svc}"
done

echo "  等待 backend/gateway 就绪…"
for i in $(seq 1 24); do
  sleep 5
  code=$(curl -s -o /dev/null -m 3 -w "%{http_code}" http://127.0.0.1:7529/api/terminal/stat/cards || true)
  echo "    t=$((i*5))s backend=$code"
  [ "$code" != "000" ] && break
done

for svc in aismp-agent agent-orchestrator; do
  systemctl enable "${svc}" >/dev/null 2>&1
  systemctl restart "${svc}"
  echo -n "  ${svc}: "
  sleep 1
  systemctl is-active "${svc}"
done

echo
echo "--- [4/5] 端口与内存 ---"
ss -tlnH 2>/dev/null | awk '{print "  "$1" "$4}' | grep -E ':(7529|8090|8123|8124|8125)$' || echo "  （端口未监听）"
echo "  --- 内存 ---"
free -m | sed 's/^/  /'

echo
echo "--- [5/5] 健康检查 ---"
printf '  backend  /api/terminal/stat/cards : '
curl -s -m 5 http://127.0.0.1:7529/api/terminal/stat/cards | head -c 150; echo
printf '  backend  /api/terminal/warehouse/list : '
curl -s -m 5 http://127.0.0.1:7529/api/terminal/warehouse/list | head -c 200; echo
printf '  agent    /doc.html : '
curl -s -o /dev/null -m 5 -w '%{http_code}\n' http://127.0.0.1:8124/doc.html
printf '  orchestrator /health : '
curl -s -m 5 http://127.0.0.1:8125/health; echo

echo
echo "########## 部署完成 $(date '+%F %T') ##########"
echo "DEPLOY-APPS-DONE"
