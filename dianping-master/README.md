# 优选生活

一个本地生活点评平台（店铺、探店笔记、优惠券秒杀），并在其上实现了一套**完全跑在 JVM 内的 RAG 知识库 + 工具调用型智能助手**。

没有 Python 运行时、没有独立检索服务：向量化、检索、重排、Agent 编排全部在同一个 Spring Boot 进程里。

---

## 功能

### 平台侧

| 模块 | 能力 |
| --- | --- |
| 用户 | 短信验证码登录、JWT（access + refresh 双令牌）、签到、个人资料、关注/共同关注 |
| 商户 | 分类浏览、按类型/名称查询、详情、Redis 缓存（含缓存穿透/击穿/雪崩处理）、GEO 附近商户 |
| 优惠券 | 秒杀下单：Lua 脚本原子扣库存 + Redisson 分布式锁一人一单 + RabbitMQ 异步落库，死信队列兜底重试 |
| 探店笔记 | 发布、浏览、点赞（ZSet 排行榜）、关注流、热门 |
| 数据统计 | 用户签到、UV 统计 |

### 智能助手

- **多轮会话**：会话与消息持久化在 MySQL，支持新建/查询/删除
- **流式回答**：SSE 推送，前端逐字渲染
- **工具调用**：模型自主决定调用哪个工具、调几次

暴露给模型的工具：

| 工具 | 作用 |
| --- | --- |
| `search_knowledge_base` | 语义检索商户知识库（按体验/氛围/特色找店），返回带 `[编号]` 的证据片段 |
| `search_shops` | 按名称/商圈搜索商户 |
| `get_shop_detail` | 查商户详情与在售优惠券 |
| `list_my_voucher_orders` | 查当前用户自己的订单 |
| `list_hot_blogs` | 查热门笔记 |

- **引用可追溯**：回答里的 `[1]` 对应前端可点击的来源卡片，每条引用带 `shopId` / `chunkId` / `contentHash` / 检索分与重排分
- **不编造**：检索结论为「没有相关内容」或「证据不足」时，工具会明确告诉模型，模型据此如实说明而不是硬编

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 17、Spring Boot 3.5.16 |
| Agent | **Spring AI 1.1.8**（`spring-ai-starter-model-openai`，对接 DeepSeek 的 OpenAI 兼容接口） |
| 向量化 | **DJL 0.33.0** + **bge-m3 ONNX**，本地推理，1024 维 |
| 向量库 | **Milvus 2.5.4**（`milvus-sdk-java`），稠密向量（HNSW/IP）+ BM25 稀疏向量，RRF 混合检索 |
| 持久层 | MySQL + MyBatis-Plus 3.5.8 |
| 缓存/锁 | Redis（Lettuce）+ Redisson 3.49 |
| 消息 | RabbitMQ（异步下单、死信重试） |
| 工具库 | Hutool 5.8.47、Lombok |
| 前端 | Vue 2 + Element UI（静态页，nginx 托管） |

---

## RAG 是怎么搭的

### 一、知识库构建

知识库的索引对象是**店铺**：一家店铺一份文档，用户的问题绝大多数也是在找店。

正文（`tb_shop_knowledge.content`）是**离线写好**的一整段连贯文字，每家 1000~1500 字：
有关联用户博客的店铺（`shop 1`、`shop 4`）参考博客写，其余按名称、类型、商圈、人均、评分、
营业时间等信息合理推测着写。它是一份静态素材，入库过程只负责搬运，不做再加工，更不调用大模型：

```
正文被改动（content 变了）
  → 定时任务比对 content_hash，发现对不上
      → 两级分块：父块 1200 字 / 叶子块 400 字
      → bge-m3 本地向量化（只向量化叶子块）
      → 父块写 MySQL（tb_shop_knowledge_chunk），叶子块写 Milvus
        （文档身份就是数字 shopId，形如 26::l2::0::1，重复入库自动覆盖）
      → 回写 content_hash / chunk_count / indexed_at
```

父块不进 Milvus 有两个原因：它不参与检索，向量化纯属白算；留在集合里还会把 BM25 的
IDF / avgdl 统计一起污染掉。自动合并时按 `chunk_id` 回 MySQL 点查即可。

因为入库不碰大模型，所以没有任务状态机、也没有重试计数：入库失败就不更新 `content_hash`，
下一轮扫描自然重试。未配置 `DEEPSEEK_API_KEY` 时知识库入库依然完全可用（只有对话功能需要 Key）。

前端展示用的短文案（介绍/特色/建议，见 `tb_shop`）和这份正文**分开维护**：
前者要分段好看，后者要一整段长文，分块才有意义。

### 二、检索管线（在线）

```
问题
 ├─ 1. 复杂度分类（Fast 模型）        simple 直接检索 / complex 先拆子问题
 ├─ 2. 子问题拆分（最多 3 个）
 ├─ 3. 召回（叶子层，top-20）：稠密向量 + BM25 稀疏向量各取 2×k，RRF 融合
 ├─ 4. 父块自动合并（同一父块命中 ≥2 个叶子 → 用父块整体替换）
 ├─ 5. 精排（Jina Rerank，cross-encoder 重排序）
 ├─ 6. 相关性评分（Grader：挑出相关片段 + 判断够不够回答）
 ├─ 7. 证据不足 → Step-back / HyDE 二选一改写 → 重新检索评分（最多一次）
 └─ 8. 证据打包：top-5 + `[n]` 编号 + 字符预算截断
```

**精排必须在评分之前**，属于「检索」这一步。先让 cross-encoder 把候选排好，Grader 看到的才是最有希望的一批；反过来先评分再精排，精排只能在评分挑剩的几条里打转。

检索会给出明确结论，供上层如实转述：

| 结论 | 含义 |
| --- | --- |
| `ANSWERABLE` | 有相关证据且足以回答 |
| `INSUFFICIENT` | 找到了相关内容，但不足以回答 |
| `NO_KNOWLEDGE` | 检索正常，但确实没有相关内容 |
| `GRADE_SKIPPED` | 未做评分（模型不可用），保留全部召回 |

### 三、Agent 工具调用

用 Spring AI 的 `@Tool` 注解声明工具，`ChatClient` 负责「请求模型 → 执行工具 → 结果回灌 → 再请求」的循环。

需要在框架之上自己补三件事：

1. **工具进度提示**：包装 `ToolCallback`，调用前发 `tool` 事件，前端显示「正在检索探店笔记」
2. **引用回收**：把 `userId` 放进 Spring AI 的 `ToolContext` 传进去，工具命中时把引用写回上下文
3. **调用次数上限**：Spring AI 的工具循环**没有任何轮次限制**，必须自己兜住，否则模型反复要求调工具会一直转

SSE 事件协议（前端据此渲染）：

| 事件 | 载荷 |
| --- | --- |
| `token` | 增量文本 |
| `tool` | 工具进度文案 |
| `citations` | 引用来源 JSON 数组 |
| `done` | 会话 id |
| `error` | 错误信息 |

---

## 快速开始

### 依赖

MySQL 3306、Redis 6379、RabbitMQ 5672 需要先自行启动；Milvus 由脚本负责拉起（Docker）。

### 配置

在项目根目录建 `.env`（已被 git 忽略，权限建议 600）：

```bash
DEEPSEEK_API_KEY=sk-xxxx

# 可选：精排。不配则检索退化为「召回顺序」，不影响使用
RERANK_BINDING_HOST=https://api.jina.ai/v1/rerank
RERANK_MODEL=jina-reranker-v2-base-multilingual
RERANK_API_KEY=jina_xxxx
```

没有配 `DEEPSEEK_API_KEY` 也能启动，只是对话与知识库摘要不可用，其余功能不受影响。

### 启动

```bash
./start-all.sh start     # Milvus（Docker）→ Java 8081 → nginx 8080
./start-all.sh status
./start-all.sh stop
```

- 前端：http://127.0.0.1:8080/
- 智能助手：http://127.0.0.1:8080/agent.html

JDK 17 由脚本自动定位，查找顺序：环境变量 `JAVA_HOME` → 系统 JDK（`/usr/lib/jvm/java-17-openjdk-amd64`）→ 项目内 `.tools/jdk17`。
每个候选都会实际执行 `java -version` 校验主版本号，避免误用 Java 8。

安装系统 JDK 17：

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk
```

### 跑测试

```bash
cd dianping-master
mvn test
```

---

## 主要配置项

`hmdp.rag.*` —— 检索相关：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `parent-size` / `parent-overlap` | 1200 / 100 | 父块大小与重叠 |
| `leaf-size` / `leaf-overlap` | 400 / 50 | 叶子块（向量检索在这一层） |
| `candidate-k` / `top-k` | 20 / 5 | 召回数 / 最终证据数 |
| `hybrid-enabled` | true | 稠密 + BM25 稀疏混合召回（Milvus 不支持稀疏字段时当次自动退化为纯向量；本语料实测与纯向量持平，见「已知限制」） |
| `hybrid-rrf-k` / `hybrid-drop-ratio-search` | 60 / 0.2 | RRF 平滑常数 / 查询侧稀疏词项裁剪比例 |
| `auto-merge` / `auto-merge-threshold` | true / 2 | 父块自动合并开关与阈值 |
| `evidence-budget` | 6000 | 证据文本字符预算 |
| `use-pipeline` | true | 是否启用完整检索管线（含 LLM 分类/评分/改写） |
| `max-rewrites` | 1 | Step-back / HyDE 改写次数上限 |
| `rerank.enabled` | true | 精排开关（还需配 `binding-host`） |

`hmdp.agent.*` —— Agent 相关：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `max-tool-calls` | 6 | 单次问答工具调用次数上限（累计，不是轮数） |
| `llm.fast-model` | deepseek-chat | 分类/评分/改写等小任务用模型 |
| `llm.timeout-ms` | 180000 | 单次问答超时（也是 SSE 连接的最长存活时间） |
| `scan-interval-ms` | 15000 | 知识库扫描间隔 |
| `scan-initial-delay-ms` | 20000 | 启动后首次扫描延迟 |

---

## 目录结构

```
dianping-master/
├── src/main/java/com/hmdp/
│   ├── controller/          # HTTP 接口
│   ├── service/             # 业务逻辑
│   ├── mapper/  entity/     # MyBatis-Plus
│   ├── interceptor/         # 登录校验 + token 刷新
│   ├── listener/            # 秒杀下单队列消费
│   ├── agent/               # 工具定义与回调包装
│   │   ├── tool/AgentTools.java        # 5 个 @Tool 方法
│   │   ├── NotifyingToolCallback.java  # 进度事件 + 配额 + 异常兜底
│   │   └── AgentToolContext.java       # 请求级上下文（userId / citations）
│   └── rag/                 # 全部检索能力
│       ├── embedding/       # bge-m3 ONNX 本地向量化
│       ├── indexing/        # 清洗 / 两级分块 / 入库
│       ├── retrieval/       # 检索管线、精排、评分、证据打包
│       └── store/           # Milvus Schema 与读写
├── src/main/resources/
│   ├── application.yaml
│   └── db/                  # 建表 SQL
└── .mvn/                    # 项目级 Maven 配置

nginx-1.18.0 dianping/html/hmdp/   # 前端静态页
start-all.sh                       # 一键启停
```

---

## 已知限制

这些是当前实现里明确的边界，不是待办清单：

- **单实例假设**：知识库靠「比对 content_hash + 定时扫描」驱动，多实例部署需要加锁
- **BM25 分词不可定制**：只要 Milvus 原生 analyzer（`{"type":"chinese"}`），没有自定义词典、同义词，也调不了 k1 / b；BM25 分数与 Milvus 版本、集合内容绑定，比较效果必须先固定口径
- **改 schema 要换集合名**：`ensureCollection` 只判断集合存不存在、不校验字段，所以这次从 `hmdp_knowledge_v1` 换成了 `hmdp_knowledge_v2`
- **子问题串行**：复杂问题拆出的多个子问题是顺序检索的，延迟线性叠加
- **无部分成功**：某个子问题检索失败会导致整次检索失败，没有 partial coverage
- **无 HITL**：用户问「那家店怎么样」这类缺主语的问题时不会主动追问，而是直接检索
- **精排依赖外部服务**：Jina 不可用时自动退化为召回顺序并记录原因，不会报错
- **知识库规模小**：目前只有 14 家店铺、16 个父块（MySQL）+ 58 个叶子块（Milvus），语料太小会让 BM25 的 IDF 不稳。固定口径的 A/B 实测结果：12 条有标准答案的问题 top1 命中 hybrid 11/12、dense 11/12，top3 都是 12/12，**混合召回与纯向量持平**；10 条模糊问题里 4 条 top1 不同，其中「人均便宜又好吃的店」纯向量排的新白鹿（正文写着性价比代表）比混合召回排的海底捞更对。默认仍然开着，想关掉只需 `hmdp.rag.hybrid-enabled: false`（不需要重建集合）
- **店铺文案靠人工维护**：`tb_shop` 的短文案和 `tb_shop_knowledge` 的正文都是离线写好的，
  平台自身没有让商家填写或自动更新的入口；用户新发博客不会触发知识库更新
