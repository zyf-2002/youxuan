#!/usr/bin/env bash
# ============================================================================
# hmdp + 智能助手 一键启停脚本（Java 17 + Spring AI）
#
#   ./start-all.sh start    启动全部服务
#   ./start-all.sh stop     停止全部服务
#   ./start-all.sh status   查看状态
#   ./start-all.sh restart  重启
#
# 架构说明：RAG 能力（向量化 / Milvus / 检索 / 检索管线）已经全部在 Java 进程内，
# 不再需要独立的 Python 服务；Agent 的工具调用由 Spring AI 的 ChatClient 驱动。
# 运行环境：Spring Boot 3.5 + Java 17（Spring AI 全系都是 Java 17 字节码，Java 8 跑不了）。
#
# 依赖的外部服务（脚本只检查，不负责启动）：
#   MySQL 3306 / Redis 6379 / RabbitMQ 5672
#
# 脚本负责启动的：
#   Milvus 19530（Docker）→ hmdp 8081（Java）→ nginx 8080
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$ROOT/.tools"
LOGS="$TOOLS/logs"
DOCKER_BIN="/mnt/c/Program Files/Docker/Docker/resources/bin"
NGINX_DIR="$ROOT/nginx-1.18.0 dianping"
UV_BIN="$TOOLS/uv-x86_64-unknown-linux-gnu/uv"

mkdir -p "$LOGS"

# 环境里 no_proxy 含 [::1]，Python 工具链会直接抛 InvalidURL，统一清掉
export NO_PROXY="localhost,127.0.0.1,::1,hf-mirror.com"
export no_proxy="$NO_PROXY"
export UV_PYTHON_INSTALL_DIR="$TOOLS/python"
export UV_CACHE_DIR="$TOOLS/uv-cache"
export PATH="$DOCKER_BIN:$TOOLS/uv-x86_64-unknown-linux-gnu:$PATH"

# ---------------------------------------------------------------------------
# JDK 17：项目已从 Java 8 升级到 Java 17
#
# 查找顺序：
#   1. 环境里已有的 JAVA_HOME
#   2. 系统安装的 JDK 17（apt install openjdk-17-jdk）
#   3. 项目内 .tools/jdk17（早期用绿色版 JDK 时留下的，作为兜底）
#
# 每个候选都会**真的执行一次 java -version 校验主版本号**，
# 避免 JAVA_HOME 指向 Java 8 时被误用（那样报错信息会很难懂）。
# ---------------------------------------------------------------------------
JAVA17_CANDIDATES=(
  "${JAVA_HOME:-}"
  "/usr/lib/jvm/java-17-openjdk-amd64"
  "$TOOLS/jdk17"
)
JAVA_HOME=""
for _cand in "${JAVA17_CANDIDATES[@]}"; do
  [[ -n "$_cand" && -x "$_cand/bin/java" ]] || continue
  if "$_cand/bin/java" -version 2>&1 | head -1 | grep -q '"17\.'; then
    JAVA_HOME="$_cand"
    break
  fi
done
unset _cand

if [[ -z "$JAVA_HOME" ]]; then
  echo "❌ 找不到可用的 JDK 17"
  echo "   项目已升级到 Java 17，Java 8 无法运行。安装方式："
  echo "     sudo apt update && sudo apt install -y openjdk-17-jdk"
  echo "   或者用绿色版：curl -L -o /tmp/jdk17.tar.gz https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  echo "                mkdir -p $TOOLS/jdk17 && tar -xzf /tmp/jdk17.tar.gz -C $TOOLS/jdk17 --strip-components=1"
  exit 1
fi
echo "  JDK 17: $JAVA_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# 全局 settings.xml 里的阿里云镜像在本地连得时好时坏，项目内这份把 central 指向 repo1
MVN_SETTINGS_OPT=()
[[ -f "$ROOT/.mvn-settings.xml" ]] && MVN_SETTINGS_OPT=(-s "$ROOT/.mvn-settings.xml")

# ---------------------------------------------------------------------------
# JVM 网络：强制 IPv4
#
# 现象：curl 能访问 api.jina.ai，但 JVM 里连接一直超时（"curl 能通、应用不通"）。
# 原因：这台机器用的是 fake-IP 代理（DNS 返回 198.18.x.x 那一段），域名同时有
#      A 和 AAAA 记录。JVM 默认优先尝试 IPv6，而当时拿到的 IPv6 是个不可达地址，
#      于是一直连不上；curl 默认走 IPv4，所以它没事。
# 处理：让 JVM 也只用 IPv4。实测强制 IPv4 后 Jina 与 DeepSeek 都正常，
#      不需要额外配置代理。
# ---------------------------------------------------------------------------
JVM_NET_OPT=("-Djava.net.preferIPv4Stack=true")

MVN_NET_OPT=("-Dspring-boot.run.jvmArguments=${JVM_NET_OPT[*]}")

# ---------------------------------------------------------------------------
# 大模型 Key（DeepSeek）—— 脚本不内置任何密钥，由你提供
#
#   1. export DEEPSEEK_API_KEY=sk-xxxx        （当前终端）
#   2. 在 $ROOT/.env 里写一行 DEEPSEEK_API_KEY=sk-xxxx
#   3. 启动后在 application.yaml 的 spring.ai.openai.api-key 填
# ---------------------------------------------------------------------------
if [[ -z "${DEEPSEEK_API_KEY:-}" && -f "$ROOT/.env" ]]; then
  DEEPSEEK_API_KEY="$(grep -E '^DEEPSEEK_API_KEY=' "$ROOT/.env" | head -1 | cut -d= -f2- | tr -d '"'"'"'')"
fi
export DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}"
export LLM_BASE_URL="${LLM_BASE_URL:-https://api.deepseek.com}"
export LLM_CHAT_MODEL="${LLM_CHAT_MODEL:-deepseek-chat}"
export LLM_FAST_MODEL="${LLM_FAST_MODEL:-deepseek-chat}"

# 精排（Jina Rerank）凭据和本地 MySQL 密码也从 .env 读。
# 精排没配的话 RerankService.enabled() 会返回 false，检索自动退化成「召回顺序」，不会报错；
# MySQL 密码对应 application.yaml 里的 ${MYSQL_PASSWORD:root}。
if [[ -f "$ROOT/.env" ]]; then
  for _var in RERANK_BINDING_HOST RERANK_MODEL RERANK_API_KEY MYSQL_PASSWORD; do
    if [[ -z "${!_var:-}" ]]; then
      _val="$(grep -E "^${_var}=" "$ROOT/.env" | head -1 | cut -d= -f2- | tr -d '"'"'"'')"
      [[ -n "$_val" ]] && export "$_var=$_val"
    fi
  done
  unset _var _val
fi

port_open() { timeout 2 bash -c "cat < /dev/null > /dev/tcp/127.0.0.1/$1" 2>/dev/null; }

wait_for_port() {
  local port="$1" name="$2" limit="${3:-60}" i=0
  while (( i < limit )); do
    if port_open "$port"; then
      echo "  ✅ $name ($port) 就绪"
      return 0
    fi
    sleep 2
    (( i += 2 ))
  done
  echo "  ❌ $name ($port) 在 ${limit}s 内未就绪"
  return 1
}

# ---------------------------------------------------------------------------
# 1. 检查外部依赖
# ---------------------------------------------------------------------------
check_dependencies() {
  echo "[1/4] 检查外部依赖"
  local missing=0
  for spec in "3306:MySQL" "6379:Redis" "5672:RabbitMQ"; do
    local port="${spec%%:*}" name="${spec##*:}"
    if port_open "$port"; then
      echo "  ✅ $name ($port)"
    else
      echo "  ❌ $name ($port) 未启动 —— 需要你先启动它"
      missing=1
    fi
  done
  return $missing
}

# ---------------------------------------------------------------------------
# 2. Milvus（只起 standalone，它会带起 etcd 和 minio）
#    刻意不启 attu：它占 8080，会和 nginx 冲突
# ---------------------------------------------------------------------------
start_milvus() {
  echo "[2/4] 启动 Milvus"
  if port_open 19530; then
    echo "  ✅ Milvus 已在运行"
    return 0
  fi

  if ! docker.exe info >/dev/null 2>&1; then
    echo "  Docker 引擎未运行，尝试启动 Docker Desktop..."
    (cd /mnt/c && nohup "/mnt/c/Program Files/Docker/Docker/Docker Desktop.exe" >/dev/null 2>&1 &)
    local i=0
    while (( i < 120 )); do
      docker.exe info >/dev/null 2>&1 && break
      sleep 5
      (( i += 5 ))
    done
    docker.exe info >/dev/null 2>&1 || { echo "  ❌ Docker 引擎启动失败"; return 1; }
  fi

  echo "  启动 milvus standalone（含 etcd/minio）..."
  # 必须显式带上 minio：SuperMew 的 compose 里 standalone 只 depends_on etcd，
  # 漏掉 minio 的话 Milvus 没有对象存储，datacoord/querycoord 起不来，
  # 会一直停在 "Proxy is not ready yet"。
  (cd "$ROOT/SuperMew" && docker.exe compose up -d etcd minio standalone >"$LOGS/milvus.log" 2>&1)
  wait_for_port 19530 "Milvus" 180

  # 端口通不代表 Proxy 就绪；冷启动时 Milvus 会返回 "Proxy is not ready yet"。
  # 这里等健康检查通过，否则 Java 启动时会连上但用不了。
  local i=0
  while (( i < 180 )); do
    if curl -s -m 5 http://127.0.0.1:9091/healthz >/dev/null 2>&1; then
      echo "  ✅ Milvus Proxy 就绪"
      return 0
    fi
    sleep 5
    (( i += 5 ))
  done
  echo "  ⚠️  Milvus 端口已开但健康检查未通过，Java 启动时可能会重试"
  return 0
}

# ---------------------------------------------------------------------------
# 3. Java 后端（RAG 能力已内置：bge-m3 ONNX + Milvus 客户端）
# ---------------------------------------------------------------------------
start_java() {
  echo "[3/4] 启动 Java 后端"
  if port_open 8081; then
    # 端口开着不等于后端可用：上一次 stop 没杀干净的残留进程也会占着 8081，
    # 这时直接「已在运行」跳过启动，nginx 就会一直 502。所以真发一个请求确认。
    # /shop-type/list 在拦截器的放行清单里，不需要登录。
    if curl -sf -m 5 http://127.0.0.1:8081/shop-type/list >/dev/null 2>&1; then
      echo "  ✅ Java 后端已在运行"
      return 0
    fi
    echo "  ⚠️  8081 被占用但后端无响应，先清理再启动"
    stop_by_port 8081 "占用 8081 的进程"
    local i=0
    while (( i < 20 )) && port_open 8081; do sleep 1; (( i++ )); done
  fi

  local model_dir
  # 单引号包裹，避免 ${RAG_MODEL_DIR:...} 被 bash 提前展开
  model_dir="$(grep -E '^ +model-dir:' "$ROOT/dianping-master/src/main/resources/application.yaml" \
    | head -1 | sed -e 's/.*model-dir: *//' -e 's/^\${RAG_MODEL_DIR://' -e 's/}$//')"
  if [[ -n "$model_dir" && ! -f "$model_dir/onnx/model.onnx" ]]; then
    echo "  ⚠️  找不到向量模型：$model_dir/onnx/model.onnx"
    echo "     下载方式：见 README 或 .tools/models 说明"
  fi

  (
    cd "$ROOT/dianping-master"
    DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" LLM_BASE_URL="$LLM_BASE_URL" \
    nohup mvn "${MVN_SETTINGS_OPT[@]}" -Dmaven.repo.local="$ROOT/.m2repo" \
      "${MVN_NET_OPT[@]}" spring-boot:run \
      >"$LOGS/hmdp.log" 2>&1 &
    echo $! >"$TOOLS/hmdp.pid"
  )
  wait_for_port 8081 "Java 后端" 240
}

# ---------------------------------------------------------------------------
# 4. nginx
# ---------------------------------------------------------------------------
start_nginx() {
  echo "[4/4] 启动 nginx"
  if port_open 8080; then
    echo "  ✅ nginx 已在运行"
    return 0
  fi
  chmod +x "$NGINX_DIR/nginx.exe" 2>/dev/null
  (cd "$NGINX_DIR" && ./nginx.exe >"$LOGS/nginx.log" 2>&1)
  wait_for_port 8080 "nginx" 30
}

# ---------------------------------------------------------------------------
# 停止相关
#
# 注意：start 与 stop 必须在「同一个终端 / 同一环境」里执行。
# 带 --unshare-pid 的隔离 shell（沙箱、容器）有独立 PID 命名空间，
# 换个终端就看不到、也杀不掉之前启动的进程。在普通终端里没有这个问题。
# ---------------------------------------------------------------------------
stop_nginx() {
  if ! port_open 8080; then
    return 0
  fi
  (cd "$NGINX_DIR" && ./nginx.exe -s quit) >/dev/null 2>&1 || true
  sleep 2
  if port_open 8080; then
    taskkill.exe /F /IM nginx.exe >/dev/null 2>&1 || true
    sleep 2
  fi
  if port_open 8080; then
    echo "  ⚠️  nginx 仍在监听 8080，请手动处理"
  else
    echo "  nginx 已停止"
  fi
}

stop_by_pidfile() {
  local file="$1" name="$2"
  [[ -f "$file" ]] || return 0
  local pid
  pid="$(cat "$file" 2>/dev/null)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 2
    kill -9 "$pid" 2>/dev/null || true
    echo "  $name 已停止 (pid $pid)"
  fi
  rm -f "$file"
}

stop_by_pattern() {
  local pattern="$1" name="$2"
  if pgrep -f "$pattern" >/dev/null 2>&1; then
    pkill -f "$pattern" >/dev/null 2>&1 || true
    sleep 2
    pkill -9 -f "$pattern" >/dev/null 2>&1 || true
    echo "  $name 已停止 (匹配 $pattern)"
  fi
}

stop_by_port() {
  local port="$1" name="$2"
  local pids
  pids="$(fuser -n tcp "$port" 2>/dev/null | tr -s ' \t' '\n' | grep -E '^[0-9]+$' | sort -u)"
  if [[ -n "$pids" ]]; then
    echo "$pids" | xargs -r kill 2>/dev/null || true
    sleep 2
    echo "$pids" | xargs -r kill -9 2>/dev/null || true
    echo "  $name ($port) 已停止"
  fi
}

# ---------------------------------------------------------------------------
# 子命令
# ---------------------------------------------------------------------------
do_start() {
  echo "=== 启动 hmdp + 智能助手（全 Java）==="

  if [[ -z "$DEEPSEEK_API_KEY" ]]; then
    echo "  ⚠️  未检测到 DEEPSEEK_API_KEY"
    echo "     摘要与对话会明确报错「大模型未配置」，知识库检索不受影响。"
    echo "     配置方式：export DEEPSEEK_API_KEY=sk-xxxx"
    echo "              或在 $ROOT/.env 写入 DEEPSEEK_API_KEY=sk-xxxx"
    echo
  else
    echo "  ✅ 大模型 Key 已配置（${DEEPSEEK_API_KEY:0:6}...，base-url=$LLM_BASE_URL，model=$LLM_CHAT_MODEL）"
  fi

  if [[ -n "${RERANK_BINDING_HOST:-}" && -n "${RERANK_API_KEY:-}" ]]; then
    echo "  ✅ 精排已配置（${RERANK_MODEL:-默认模型} @ $RERANK_BINDING_HOST）"
  else
    echo "  ℹ️  未配置精排（RERANK_BINDING_HOST / RERANK_API_KEY），检索使用召回顺序"
  fi
  echo "  ℹ️  JVM 强制 IPv4（${JVM_NET_OPT[0]}）"

  check_dependencies || echo "  ⚠️  外部依赖有缺失，后续步骤可能失败"
  start_milvus && start_java && start_nginx

  echo
  echo "=== 访问地址 ==="
  echo "  前端      http://127.0.0.1:8080/"
  echo "  智能助手  http://127.0.0.1:8080/agent.html"
  echo "  Java API  http://127.0.0.1:8081/"
  echo
  echo "=== 日志 ==="
  echo "  tail -f $LOGS/hmdp.log"
  echo
  echo "说明：RAG 能力已在 Java 进程内，无需单独启动 Python 服务。"
}

do_stop() {
  echo "=== 停止服务 ==="
  stop_nginx

  stop_by_pidfile "$TOOLS/hmdp.pid" "Java 后端"
  stop_by_pattern "spring-boot:run" "Java 后端"
  # spring-boot:run 会 fork 出真正的应用 JVM，它的命令行里没有 "spring-boot:run"，
  # 只有主类名，所以这个 pattern 不能省，否则残留进程会一直占着 8081。
  stop_by_pattern "HmDianPingApplication" "Java 后端"
  stop_by_pattern "hm-dianping" "Java 后端"
  stop_by_port 8081 "Java 后端"

  # 等端口真正释放，否则紧跟着执行的 start 会误判成「已在运行」而跳过启动
  local w=0
  while (( w < 15 )) && port_open 8081; do sleep 1; (( w++ )); done

  echo
  echo "=== 剩余监听 ==="
  local left=0
  for p in 8080 8081; do
    if port_open "$p"; then
      echo "  ⚠️  $p 仍在监听（可能是在别的终端/PID 命名空间里启动的）"
      left=1
    fi
  done
  [[ $left -eq 0 ]] && echo "  8080 / 8081 均已释放 ✅"
  echo
  echo "  Milvus 容器保持运行。如需停止："
  echo "    docker.exe compose -f SuperMew/docker-compose.yml stop standalone"
}

do_status() {
  echo "=== 服务状态 ==="
  for spec in "3306:MySQL" "6379:Redis" "5672:RabbitMQ" "19530:Milvus" "8081:Java 后端" "8080:nginx"; do
    local port="${spec%%:*}" name="${spec##*:}"
    if port_open "$port"; then
      printf "  %-12s %-6s ✅\n" "$name" "$port"
    else
      printf "  %-12s %-6s ❌\n" "$name" "$port"
    fi
  done

  # 知识库状态只依赖 MySQL，和 Java 起没起无关，所以不放在 port_open 判断里
  echo
  echo "=== 知识库入库情况 ==="
  "$UV_BIN" run --python 3.12 --with pymysql python - <<'PY' 2>/dev/null | tail -8
import os
import pymysql
try:
    c = pymysql.connect(host='127.0.0.1', port=3306, user='root',
                        password=os.environ.get('MYSQL_PASSWORD', ''), database='hmdp', charset='utf8mb4')
    cur = c.cursor()
    cur.execute("SELECT COUNT(*) FROM tb_shop_knowledge")
    total = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM tb_shop_knowledge WHERE content_hash IS NOT NULL")
    indexed = cur.fetchone()[0]
    cur.execute("SELECT COALESCE(SUM(chunk_count), 0) FROM tb_shop_knowledge")
    chunks = cur.fetchone()[0]
    print(f"  店铺正文: {total} 家，已入库 {indexed} 家，共 {chunks} 个分块")
    if indexed < total:
        print(f"  ⚠️  还有 {total - indexed} 家未入库（启动后等一个扫描周期）")
    cur.execute("SELECT COUNT(*) FROM tb_agent_conversation")
    print('  会话数:', cur.fetchone()[0])
    c.close()
except Exception as e:
    print('  （读取失败）', e)
PY
}

case "${1:-start}" in
  start)   do_start ;;
  stop)    do_stop ;;
  status)  do_status ;;
  restart) do_stop; echo; do_start ;;
  *) echo "用法: $0 {start|stop|status|restart}"; exit 1 ;;
esac
