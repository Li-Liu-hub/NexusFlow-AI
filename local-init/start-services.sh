#!/usr/bin/env bash
# ============================================================
# agent-platform 本机启动脚本（Java 侧 4 个服务）
# 用法： ./start-services.sh <backend|gateway|interface|agent|nacos>
# 关键：必须 unset SERVER_PORT / SERVER__PORT —— 本机环境里存在
#       SERVER__PORT=53176，Spring Boot 会把它当成 server.port 覆盖配置文件
# ============================================================
set -uo pipefail

# 统一清掉会劫持端口的进程级环境变量
unset SERVER_PORT SERVER__PORT

JDK8=/d/env/jdk-8
JDK21=/d/env/jdk-21
MVN_REPO="D:\\Office software\\mainfile\\apache-maven-3.9.9-bin\\apache-maven-3.9.9\\mvn_repo"
BASE="D:/亚信科技/学习日志/比赛/agent-platform"

SVC="${1:-}"

run_java8() {  # $1=jar  $2=port  $3=显示名（aismp-interface 仍是 SB 2.7.x + JDK8，未参与本次重构）
  export JAVA_HOME="$JDK8"
  export PATH="$JDK8/bin:$PATH"
  echo "启动 $3 (端口 $2) ..."
  exec "$JDK8/bin/java.exe" -Dfile.encoding=UTF-8 -jar "$1" --server.port="$2"
}

run_java21() {  # $1=jar  $2=端口  $3=显示名  [$4..=额外 java 参数，透传]
  export JAVA_HOME="$JDK21"
  export PATH="$JDK21/bin:$PATH"
  local jar="$1" port="$2" name="$3"; shift 3
  echo "启动 $name (端口 $port) ..."
  exec "$JDK21/bin/java.exe" -Dfile.encoding=UTF-8 -jar "$jar" --server.port="$port" "$@"
}

case "$SVC" in
  backend)
    # 阶段3：SB 2.7.0 + JDK8 → 4.0.7 + JDK21；且升级后要求 Nacos 服务注册。
    # 凭据说明：application.yml 里是 ${MYSQL_APP_PW:} / ${REDIS_PW:} 形式（不把密码写进仓库）。
    # 本机没有 api_user（那是远端加固 90-harden.sh 建的），本地统一用 root/123456 连 api_db
    #（与 aismp-agent / 内核走的是同一套本机凭据）。
    export MYSQL_APP_USER="${MYSQL_APP_USER:-root}"
    export MYSQL_APP_PW="${MYSQL_APP_PW:-123456}"
    export REDIS_PW="${REDIS_PW:-123456}"
    export RABBIT_USER="${RABBIT_USER:-guest}"
    export RABBIT_PW="${RABBIT_PW:-guest}"
    run_java21 "$BASE/aismp-backend/target/aismp-backend-0.0.1-SNAPSHOT.jar" 7529 "aismp-backend"
    ;;
  gateway)
    # 阶段3：SB 2.7.5 + JDK8 → 4.0.7 + JDK21（Dubbo 已整体移除，鉴权链改 HTTP + Nacos lb）
    run_java21 "$BASE/aismp-backend/aismp-gateway/target/aismp-gateway-0.0.1-SNAPSHOT.jar" 8090 "aismp-gateway"
    ;;
  interface)
    run_java8 "$BASE/aismp-backend/aismp-interface/target/aismp-interface-0.0.1-SNAPSHOT.jar" 8123 "aismp-interface"
    ;;
  digital-human)
    # 数字员工内核（端口 8126）：装配（角色/技能/知识库/工具）+ ReAct。
    # 编排层 kb 路线会把请求转到它；管理端 /admin/digitalHuman/** 也代理到它。
    # 曾漏了这个分支 —— start-all.sh 也就没启动它，导致知识问答路线整条不可用。
    JAR="$BASE/aismp-digital-human/target/aismp-digital-human-0.0.1-SNAPSHOT.jar"
    if [ ! -f "$JAR" ]; then
      echo "ERROR: 找不到 $JAR。请先构建：cd aismp-digital-human && mvn package -DskipTests" >&2
      exit 1
    fi
    export MIMO_API_KEY="${MIMO_API_KEY:?缺少环境变量 MIMO_API_KEY（内核对话模型 Key）}"
    # application.yml 里 datasource 已改为无兜底（${MYSQL_USER} / ${MYSQL_PASSWORD}），
    # 这里必须注入，否则本机启动即失败。开发默认值与本机 MySQL 一致。
    # （digital-human 只用 MySQL，不连 PG —— 原有一行多余的 PG_PASSWORD 已删除）
    export MYSQL_USER="${MYSQL_USER:-root}"
    export MYSQL_PASSWORD="${MYSQL_PASSWORD:-123456}"
    run_java21 "$JAR" 8126 "aismp-digital-human" "${@:2}"
    ;;
  agent)
    # aismp-agent：阶段1 已升 Spring Boot 4.0.7 + JDK21（README 里写的 JDK 8 是错的）
    if [ -z "${MIMO_API_KEY:-}" ]; then
      echo "ERROR: 缺少环境变量 MIMO_API_KEY（aismp-agent 的对话模型 Key）" >&2
      exit 1
    fi
    export MIMO_API_KEY
    # application.yml 的 spring.datasource.password 引用 ${PG_PASSWORD}，缺了会在
    # PgVectorStore 建连时抛 "server requested SCRAM-based authentication, but no password was provided"
    export PG_PASSWORD="${PG_PASSWORD:-123456}"
    # 嵌入服务地址（本机 llama.cpp 托管的 bge-m3，见 D:\env\start-bge-m3-8099.sh）
    export LOCAL_EMBEDDING_BASE_URL="${LOCAL_EMBEDDING_BASE_URL:-http://127.0.0.1:8099}"

    # jar 直接用仓库构建产物。
    # （历史说明：这里曾有一段「Spring AI 1.0.3 → 2.0.0 迁移未完成、必编译失败」的
    #  ALT_JAR 回退到 /d/env/builds/agent-103 的逻辑 —— 实测 2.0.0-M1 与 1.0.3 API 兼容，
    #  升级零代码改动即编译通过，该回退与注释已过时删除。）
    JAR="$BASE/aismp-agent/target/aismp-agent-0.0.1-SNAPSHOT.jar"
    if [ ! -f "$JAR" ]; then
      echo "ERROR: 找不到 $JAR。请先构建：cd aismp-agent && mvn package -DskipTests" >&2
      exit 1
    fi

    export JAVA_HOME="$JDK21"
    export PATH="$JDK21/bin:$PATH"
    echo "启动 aismp-agent (端口 8124) ..."
    exec "$JDK21/bin/java.exe" -Dfile.encoding=UTF-8 \
      -jar "$JAR" \
      --server.port=8124 "${@:2}"
    ;;
  nacos)
    # Nacos 3.2.4（2026-10-06 起）。
    # 两个硬性前提（都踩过）：
    #   1. **必须 JDK 17+** —— 用 JDK 8 起会报 UnsupportedClassVersionError
    #      （class file version 61.0 > 52.0），本机用 D:/env/jdk-21。
    #   2. **Derby 嵌入式存储是单实例锁** —— 同一 data 目录被别的实例 booted 后，
    #      再起会报 "ERROR XSDB6: Another instance of Derby may have already booted"。
    #      判断是否已在跑：netstat 看 8848/9848 是否监听。
    # 端口组（3.x 把 console 拆了出去）：8848 主服务 / 9848 主服务 gRPC /
    #   8849 console（conf 里 nacos.console.port=8849；3.x 默认是 8080）/ 9849 console gRPC。
    # 注：3.x 已移除 v1 OpenAPI（/nacos/v1/ns/service/list 会 501），
    #   Dubbo 走 gRPC(9848) 不受影响；若有脚本依赖 v1 HTTP API 需改。
    CD="/d/env/nacos-3.2.4"
    export JAVA_HOME="$JDK21"
    echo "启动 Nacos 3.2.4 standalone (8848 + console 8849, JDK 21) ..."
    exec "$JDK21/bin/java.exe" \
      -Dnacos.standalone=true \
      -Dnacos.home="D:\\env\\nacos-3.2.4" \
      -Dloader.path="$CD/plugins,$CD/plugins/health,$CD/plugins/cmdb,$CD/plugins/selector" \
      -Xms512m -Xmx512m -Xmn256m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=128m -Xss512k \
      -jar "$CD/target/nacos-server.jar" \
      --spring.config.additional-location="$CD/conf/" \
      --logging.config="$CD/conf/nacos-logback.xml" \
      --server.max-http-header-size=524288 \
      nacos.nacos
    ;;
  nacos2)
    # 历史版本 Nacos 2.1.0（备份目录 D:/env/nacos-2.1.0-backup），JDK 8。
    # 原 /d/env/nacos 目录已于 2026-10-06 升级 3.x 时移除，故改指备份目录。
    export JAVA_HOME="$JDK8"
    CD="/d/env/nacos-2.1.0-backup"
    echo "启动 Nacos 2.1.0 standalone (8848) ..."
    exec "$JDK8/bin/java.exe" \
      -Dnacos.standalone=true \
      -Dnacos.home="D:\\env\\nacos-2.1.0-backup" \
      -Dloader.path="$CD/plugins,$CD/plugins/health,$CD/plugins/cmdb,$CD/plugins/selector" \
      -Xms512m -Xmx512m -Xmn256m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=128m -Xss512k \
      -jar "$CD/target/nacos-server.jar" \
      --spring.config.additional-location="$CD/conf/" \
      --logging.config="$CD/conf/nacos-logback.xml" \
      --server.max-http-header-size=524288 \
      nacos.nacos
    ;;
  *)
    echo "用法: $0 {backend|gateway|interface|agent|nacos|nacos2}"
    exit 1
    ;;
esac
