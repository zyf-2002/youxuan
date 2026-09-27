# 优选生活（hmdp）非 AI 后端技术分析报告

> 项目：`/home/zyf/springboot/hmdp/dianping-master`
> 技术栈实测：Spring Boot **3.5.16**、Java **17**、MyBatis-Plus **3.5.8**、Redisson **3.49.0**、Hutool **5.8.47**、spring-boot-starter-amqp、spring-boot-starter-data-redis(Lettuce)，MySQL 8（`pom.xml:8,18,73,80,91`、`src/main/resources/application.yaml:1-75`）
> 说明：本报告只覆盖非 AI 部分。凡代码里没有出现的，我都明确写「未实现」。

---

## 0. 整体结论速览

| 经典考点 | 是否真的实现 | 实现位置 |
|---|---|---|
| 缓存穿透 | ✅ 布隆过滤器 + 空值缓存（双保险） | `ShopServiceImpl#queryById`、`CacheClient#setNullValue` |
| 缓存击穿 | ✅ 逻辑过期 + Redisson 互斥锁（混合） | `CacheClient#queryWithLogicalExpire` / `#queryWithMutexAndLogicalExpire` |
| 缓存雪崩 | ❌ **无随机 TTL**，全项目没有任何 TTL 抖动 | — |
| 秒杀 Redis+Lua 原子预扣 | ✅ | `src/main/resources/seckill.lua` |
| 一人一单 | ✅ Lua `sismember` + DB 唯一索引 `uk_user_voucher` | `seckill.lua:33-35`、`src/main/resources/db/hmdp.sql:1257` |
| 异步下单 + MQ | ✅ 手写 rabbit 拓扑（非注解死信，显式转发） | `QueueConfig`、`SeckillVoucherListener` |
| 发布确认 / 手动 ACK / 延迟重投 / 死信 | ✅ 四项齐全 | `VoucherOrderServiceImpl#initPublisherCallbacks`、`application.yaml:9-18` |
| 幂等 | ✅ DB 主键 + 唯一索引 + 状态判定 | `VoucherOrderServiceImpl#completeVoucherOrder` |
| 补偿（回滚 Redis 预留） | ✅ `abandonSeckill.lua` | `src/main/resources/abandonSeckill.lua` |
| 秒杀用 Redisson 分布式锁 | ❌ **没有**，秒杀链路上没有任何 Redisson 锁 | — |
| 限流 / 熔断 / Sentinel | ❌ 完全没有 | — |
| JWT 双 token（access+refresh） | ❌ **没有**，是不透明 UUID token + Redis 滑动续期 | `UserServiceImpl#createLoginToken` |
| 点赞 ZSet 排行榜 | ⚠️ 点赞记录是 ZSet，但「热门榜」查的是 DB 的 `liked` 列 | `BlogServiceImpl#queryHotBlog` |
| 关注流用 Redis Set 交集 | ⚠️ 交集只用于「共同关注」；关注流是 **写扩散 push** | `FollowServiceImpl#followCommons`、`BlogServiceImpl#saveBlog` |

---

## 1. 秒杀 / 闪购下单（秒杀链路）

### 1.1 入口与分区职责

- HTTP 入口：`VoucherOrderController#seckillVoucher`，`POST /voucher-order/seckill/{id}`（`src/main/java/com/hmdp/controller/VoucherOrderController.java:20-23`）。该接口**没有** `@PublicApi`，必须登录。
- 状态查询：`GET /voucher-order/status/{id}`（同文件 `:25-28`）。
- 类级 javadoc 明确写了三段式分工（`src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java:37-52`）：Redis 只做「闸门 + 状态 + 待投递」；MQ 负责可靠投递、重试、放弃；**数据库只有消费者一条写入路径**（旧的「每 10 秒扫表补写数据库」的定时任务已被删除，替换为只补发消息的 `republishUnconfirmedOrders()`）。

### 1.2 从 HTTP 到 DB 的完整时序

1. **`VoucherOrderServiceImpl#seckillVoucher(Long voucherId)`**（`VoucherOrderServiceImpl.java:126-159`）
   - `UserHolder.getUser().getId()` 取当前用户（`TokenInterceptor` 在请求线程已写入 ThreadLocal）。
   - `redisIdWorker.nextId("order")` 预生成全局订单号（`:129`）。
   - `System.currentTimeMillis()` 作为抢购时间戳。
2. **执行 `seckill.lua`**（`:132-145`），一次往返完成原子操作。传入 4 个 KEY、5 个 ARGV：
   - `KEYS[1]` = `seckill:stock:{voucherId}`、`KEYS[2]` = `seckill:order:{voucherId}`、`KEYS[3]` = `seckill:order:status:{orderId}`、`KEYS[4]` = `seckill:order:undelivered`（全局单 key 的 ZSet）
   - `ARGV[1..5]` = userId / voucherId / orderId / now(ms) / 状态 TTL 秒（`SECKILL_ORDER_STATUS_TTL_SECONDS = 3600`）
3. **Lua 内部逻辑**（`src/main/resources/seckill.lua:24-53`）：
   - `GET stockKey`；`nil` → 返回 `-1`（库存未初始化）；`<= 0` → 返回 `1`（库存不足）
   - `SISMEMBER orderKey userId == 1` → 返回 `2`（已抢过，一人一单的唯一现场判据）
   - 否则：`INCRBY stockKey -1` → `SADD orderKey userId` → `HSET statusKey status=PROCESSING message=ORDER_PROCESSING userId voucherId createTime` → `EXPIRE statusKey 3600` → `ZADD undeliveredKey createTime orderId` → 返回 `0`
   - **全程单脚本原子**，无 Java 侧竞态窗口。
4. **返回码翻译**（`VoucherOrderServiceImpl.java:147-150` + `:412-420`）：`1→"库存不足"`、`2→"你已抢到资格，请稍后查看订单"`、其他非 0（即 `-1`）→`"活动库存未初始化"`。
5. **发消息**：`sendOrderMessage(order)`（`:252-264`），`rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_ROUTING_KEY, JSONUtil.toJsonStr(order), new CorrelationData(orderId))`。消息体**自带完整订单数据**，消费者不依赖 Redis 即可落库；发送异常被 catch，只记日志（依赖待投递 ZSet 补发）。
6. **HTTP 立即返回订单号**（`:158`），注释明确「返回只代表抢到资格，不代表 MySQL 已写完」（`:121-124`）。
7. **前端轮询** `querySeckillOrderStatus(orderId)`（`:168-196`）：**先查 DB**（`getById`，命中即 `SUCCESS`，并校验 `order.getUserId()` 归属，防越权）；DB 没有再查 Redis hash `seckill:order:status:{orderId}`；hash 也没有 → `UNKNOWN`（「订单状态已过期或不存在」）。Redis 分支同样校验 hash 里的 `userId` 归属（`:185-188`）。
8. **消费**：`SeckillVoucherListener#receiveOrder`（`src/main/java/com/hmdp/listener/SeckillVoucherListener.java:39-79`）→ `IVoucherOrderService#handleVoucherOrder`（`:202-205`）→ `completeVoucherOrder(order, "mq")`。
9. **落库事务**（`:330-354`）：`transactionTemplate.executeWithoutResult(status -> createVoucherOrder(order))` → `save(order)` + `voucherService.update().setSql("stock = stock - 1").eq("id", voucherId).update()`（`:310-324`）。

### 1.3 一人一单的三重保障

| 层 | 机制 | 位置 |
|---|---|---|
| Redis（第一道，挡流量） | Lua `SISMEMBER seckill:order:{voucherId} userId` | `seckill.lua:33-35` |
| DB（最终一致，兜底） | 唯一索引 `UNIQUE INDEX uk_user_voucher(user_id, voucher_id)` | `src/main/resources/db/hmdp.sql:1257` |
| 应用（幂等预检） | `getById(orderId) != null` 直接跳过；`isAbandoned(orderId)` 判断是否已作废 | `VoucherOrderServiceImpl.java:331-340` |

**注意**：秒杀链路中**没有** Redisson 锁。全项目 `RLock`/`getLock` 只出现在 `CacheClient`（缓存重建），`SimpleRedisLock` 类存在但无任何调用点（详见第 4 节）。

### 1.4 MQ 拓扑与可靠性（`src/main/java/com/hmdp/config/QueueConfig.java`）

- Exchange：`seckill.order.exchange`（`DirectExchange`，`:32,49-52`）
- 队列与路由键：

| 队列 | 路由键 | 特性 | 代码 |
|---|---|---|---|
| `seckill.order.queue` | `seckill.order` | durable 主队列 | `:34,38,54-57` |
| `seckill.order.retry.queue` | `seckill.order.retry` | durable + `ttl=15000ms` + `deadLetterExchange=seckill.order.exchange` + `deadLetterRoutingKey=seckill.order`（TTL 到期由 broker 送回主队列） | `:35,39,59-66` |
| `seckill.order.dead.queue` | `seckill.order.dead` | durable，**无消费者**，堆着等人看，不自动重放 | `:36,40,68-71` |

- 常量：`RETRY_DELAY_MS = 15000`（`:43`）、`MAX_RETRY_COUNT = 20`（`:45`，注释算得总窗口 ≈ 5 分钟）、重试计数头 `RETRY_COUNT_HEADER = "x-retry-count"`（`:47`）。
- 生产端可靠性配置（`application.yaml:9-18`）：`publisher-confirm-type: correlated`、`publisher-returns: true`、`template.mandatory: true`、`listener.simple.acknowledge-mode: manual`。

### 1.5 发布确认与「待投递」集合（关键设计）

`VoucherOrderServiceImpl#initPublisherCallbacks()` 用 `@PostConstruct` 注册回调（`:94-112`）：

- **ConfirmCallback**（`:96-107`）：解析 `correlationData.getId()`（就是 orderId）。判定 `ack && !correlationData.getReturned()` 才算真成功 → `ZREM seckill:order:undelivered orderId`。注释点出一个容易踩的坑：**被退回的消息 broker 也会回 ack**（它确实收下了，只是路由不出去），所以必须叠加 `getReturned()` 判断。
- **ReturnsCallback**（`:109-111`）：只打 ERROR 日志（exchange / routingKey / body），不做其他处理；不回删标记，交给补发任务。

### 1.6 补发任务（replaces 旧的「定时扫表补写 DB」）

`VoucherOrderServiceImpl#republishUnconfirmedOrders()`，`@Scheduled(fixedDelay = 5000)`（`:274-299`）：

- 扫描 `ZSET seckill:order:undelivered`，`rangeByScore(key, 0, now - UNDELIVERED_MIN_AGE_MS, 0, UNDELIVERED_SCAN_BATCH_SIZE)`，即只捞 **分数早于 5 秒前** 的，单批上限 **50**（`UNDELIVERED_MIN_AGE_MS = 5000L`、`UNDELIVERED_SCAN_BATCH_SIZE = 50`，`:57-60`）。
- 对每条：`getOrderFromRedis(orderId)` 从状态 hash 里重建 `VoucherOrder`（`:392-407`，只取 `userId`/`voucherId`）；重建不出来（状态已被清理）→ 直接把该成员从 ZSet 移除（`:286-290`）。
- 重建成功 → **先把分数顶到当前时间**（`:291-292，避免同一轮反复重发）→ `sendOrderMessage(order)`。
- **只发消息，绝不写数据库**（注释 `:266-273`）。

### 1.7 消费端：重投 / 死信 / 补偿（`SeckillVoucherListener`）

- 消费成功（`handleVoucherOrder` 正常返回）→ `channel.basicAck(deliveryTag, false)`（`:47-48`）。
- 消费抛异常（即「没落库」）→ 读 `x-retry-count`（`:105-118`，缺头/头坏都按 0）：
  - `retryCount < 20` → `sendToRetry(body, retryCount + 1)`，带 header 发到 `seckill.order.retry`（`:53-55, 84-93`），15 秒后回主队列。
  - `retryCount == 20` → 先 `sendToDead(body)` 进死信（`:58-63`），再 `abandonOrder(order)` 撤销 Redis 预留（`:64`）。
    - **顺序是刻意的**（注释 `:59-60`）：先死信后撤预留，中间挂掉留下的是「预留还在」这种保守状态，不会超卖。
    - `abandonOrder` 返回 `false`（Redis 不可用）→ 不 ACK，`sendToRetry(body, retryCount + 1)` 过 15 秒回来重试撤销；因为 `if (retryCount == MAX_RETRY_COUNT)` 是等值判断，计数继续涨也不会重复塞死信（`:66-68`）。
- 转发本身失败 → `channel.basicNack(deliveryTag, false, true)`（requeue=true），原消息退回 broker（`:71-76`）。
- **消息体解析失败**：`JSONUtil.toBean` 在 `try` 块**之前**（`:44`），异常直接抛给容器。类 javadoc 自陈（`:26-27`）：手动 ACK 模式下容器不会替你 nack，这条消息会一直挂在 unacked，应用重启时才被重投、再失败一次。这是一个**已知未处理的边界**。

### 1.8 落库与幂等细节

`createVoucherOrder`（`:310-324`）：
- `save(voucherOrder)` 失败抛 `IllegalStateException`。
- 扣库存直接打 `tb_voucher`：`update().setSql("stock = stock - 1").eq("id", voucherId).update()`。注释说明：券只有一张表（`tb_seckill_voucher` 已合并进 `tb_voucher`，SQL 里已无 seckill 表），且**故意不判 `stock > 0`、不看影响行数**，理由是「Redis 预占在前，MySQL 库存只会比 Redis 多」。
- **刻意不吞 `DuplicateKeyException`**（`:301-309`）：如果在这里 catch，异常没穿过事务边界，事务会停留在「已回滚但仍要提交」状态，提交时抛 `UnexpectedRollbackException`。所以让它抛出去。
- `completeVoucherOrder` 在**事务之外**捕获 `DuplicateKeyException` → 视为「同一条单的另一份消息已写进去了」，算成功并清理状态（`:344-350`）。
- 已作废的单不允许补落库：`isAbandoned(orderId)` 读 `seckill:order:status:{orderId}` 的 `status` 字段是否等于 `"FAILED"`；Redis 异常时**按未作废处理**（宁可重复一次判定，也不把订单写丢）（`:361-369`）。

### 1.9 状态机与补偿

Redis 侧订单状态 hash `seckill:order:status:{orderId}`，字段 `status / message / userId / voucherId / createTime`：

```
（Lua 预占成功）PROCESSING
   ├── 消费者落库成功 → cleanupOrderState() 删除 hash（DB 有行 = SUCCESS，由 order 表存在性表达）
   ├── 重投耗尽 + 进死信 → abandonSeckill.lua 置 FAILED("下单失败，请重新抢购")
   └── 处理过程中进程崩溃 → hash 靠 3600s TTL 自然过期 → 前端查到 UNKNOWN
```

补偿脚本 `src/main/resources/abandonSeckill.lua:13-23`（`VoucherOrderServiceImpl#abandonOrder` 调用，`:219-247`）：
- `SREM seckill:order:{voucherId} userId`；**只有 `removed == 1` 时才 `INCR seckill:stock:{voucherId}`**（`:13-18`）。注释说明：同一条单的重复消息可能都走到这里，不加这个判断会把库存加多，Redis 库存高于 DB → 可能超卖。
- 无条件 `ZREM seckill:order:undelivered orderId`（`:21`），防止补发任务把已作废的单再发出去。
- 返回 `removed`；Java 侧只把「Redis 异常」当作失败（返回 `false`，调用方不 ACK），消息缺 `userId/voucherId` 时返回 `true`（重试也没用，只记 ERROR 交人工，`:220-224`）。

### 1.10 Redis 库存标记的清理路径

| Key | 写入 | 清理 |
|---|---|---|
| `seckill:stock:{voucherId}` | `VoucherServiceImpl#addVoucher`（`:41-42`）、`#initMissingStockInRedis`（`:55-57`，`setIfAbsent` 只补不覆盖） | 仅 `abandonSeckill.lua` 里 `INCR` 回加；**没有 TTL** |
| `seckill:order:{voucherId}`（已购用户 Set） | `seckill.lua:38` | 仅 `abandonSeckill.lua` 的 `SREM`；**没有 TTL** |
| `seckill:order:status:{orderId}` | `seckill.lua:39-47` | `cleanupOrderState()` 显式 `DEL`（`:378-386`），兜底 3600s TTL |
| `seckill:order:undelivered`（ZSet） | `seckill.lua:51` | 发布确认 ack（`:102`）、`cleanupOrderState`（`:381`）、补发任务清理无状态的成员（`:288`）、`abandonSeckill.lua` 的 `ZREM` |
| Redis 库存补写 | 运维接口 `POST /voucher/stock/init` → `VoucherServiceImpl#initMissingStockInRedis()`（`VoucherController.java:52-55`） | 启动时**不会**自动补（没有 ApplicationRunner 调它） |

`cleanupOrderState` 的删序刻意「先删状态 hash 再删待投递标记」（注释 `:371-377`）：万一只做一半，剩下的是「标记还在、状态没了」，补发任务看到没状态会把标记清掉，自己能收尾。

### 1.11 明确缺失 / 被代码自陈的弱点

- **不校验券的上下架和时间窗**：`VoucherOrderServiceImpl.java:117-120` 直言「这里不校验券的上下架和时间窗：能不能买只看 Redis 里有没有库存 key。前端会自己按时间把按钮置灰，但接口是可以被直接调用的」。全项目 grep `beginTime/endTime/status` 在 service 层无任何业务校验；只有 `VoucherMapper.xml:10` 的列表查询带了 `AND status = 1`。
- **DB 库存无下界保护**：`tb_voucher.stock` 是 `int(8) NOT NULL DEFAULT 0`（有符号，`db/hmdp.sql:1232`），扣减语句也没有 `stock > 0` 条件，超卖防线完全押在「Redis 预占 + 单写路径」这个不变量上。
- **补发任务无分布式锁**：`@Scheduled` 裸跑，多实例部署会重复补发（重复消息本身靠唯一索引兜住，但会产生额外投递）。
- **DLQ 不重放**是刻意设计（注释 `:210-213`）：重放会与已归还的预留冲突，可能超卖。

---

## 2. 缓存（Redis）

### 2.1 统一封装：`src/main/java/com/hmdp/utils/CacheClient.java`

`@Component`，构造器注入 `StringRedisTemplate` + `RedissonClient`（`:22-28`）。

**方法清单与实测使用情况：**

| 方法 | 语义 | 是否被调用 |
|---|---|---|
| `set(key, value, time, unit)`（`:30-32`） | 普通写 + 物理 TTL | 被内部 `setNullValue` 之外的路径间接使用 |
| `setWithLogicalExpire(key, value, time, unit)`（`:34-39`） | 包 `RedisData{expireTime, data}` 后写入，**不设 TTL** | 是（`ShopCacheTest:40`） |
| `queryWithPassThrough(...)`（`:41-60`） | 标准 cache-aside + 空值缓存 | ❌ **全项目无调用点（死代码）** |
| `queryWithLogicalExpire(...)`（`:62-99`） | 逻辑过期 + 异步重建 | ✅ 生产路径，`ShopServiceImpl#queryById:58-59` |
| `queryWithMutexAndLogicalExpire(...)`（`:102-128`） | Redisson 互斥锁 + 双检 | 被 `queryWithLogicalExpire` 内部调用 |

### 2.2 Key 命名与 TTL（`src/main/java/com/hmdp/utils/RedisConstants.java`）

| 常量 | 值 | 说明 |
|---|---|---|
| `CACHE_SHOP_KEY` | `cache:shop:` | 店铺详情缓存 |
| `CACHE_SHOP_TTL` | `30L`（配合 `TimeUnit.MINUTES`） | 逻辑过期时间 = now + 30min |
| `CACHE_NULL_TTL` | `2L`（分钟） | 空值缓存 |
| `LOCK_SHOP_KEY` | `lock:shop:` | 缓存重建锁前缀 |
| `LOCK_SHOP_TTL` | `10L`（秒） | 锁的 leaseTime |
| `SHOP_BLOOM_KEY` | `shop:bloom` | 布隆过滤器 |
| `SHOP_TYPE_KEY` | `shop_type:` | 分类缓存（List） |

### 2.3 逻辑过期（缓存击穿）路径逐行说明

`CacheClient#queryWithLogicalExpire`（`:62-99`）：

1. `GET key`：
   - `StrUtil.isBlank(json)` 且 `json != null`（即缓存的是 `""` 空值）→ **直接返回 null**（`:66-69`）——这是空值缓存的读侧。
   - `json == null`（key 根本不存在，逻辑过期模式下**从不设物理 TTL**，所以 key 不该不存在）→ 转入 `queryWithMutexAndLogicalExpire`（`:70`）。
2. 解析 `RedisData`，取 `expireTime`；`expireTime == null` 视为普通缓存，直接反序列化返回（`:73-77`，兼容/降级分支）。
3. `expireTime.isAfter(now)` → **直接返回数据，不碰 DB**（`:79-81`）。
4. 已逻辑过期 → **开异步重建并立刻返回旧数据**（`:83-98`）：
   - 锁 key = `RedisConstants.LOCK_SHOP_KEY + key`，即 `lock:shop:cache:shop:{id}`（注意此处把完整缓存 key 拼在锁前缀后，命名略冗余）。
   - `CACHE_REBUILD_EXECUTOR.submit(...)` 提交到 **`Executors.newFixedThreadPool(10)`**（`:20`）。
   - 线程内：`redissonClient.getLock(lockKey)` → `tryLock(lock)` → 成功则 `dbFallback.apply(id)` + `setLogicalExpireValue(...)`；`finally` 里 `unLock(lock)`。
   - 缓存重建失败只记 `log.error("Failed to rebuild cache, key={}", key, e)`（`:91-92`）。

`CacheClient#queryWithMutexAndLogicalExpire`（`:102-128`，注释自称「当 redis key 为 null 并且不是空字符串的时候需要调用」）：
- 取同一把 Redisson 锁，`tryLock` **失败** → `Thread.sleep(50)` 后**递归调用 `queryWithLogicalExpire`**（`:108-111`）。没有重试上限，靠 Redis 侧未命中快速返回。
- 抢到锁后**二次检查**（`:113-120`）：`isNotBlank` → 反序列化返回；`json != null`（空串）→ 返回 null；否则 `dbFallback.apply(id)` → `setLogicalExpireValue`。

`setLogicalExpireValue`（`:146-152`）：DB 返回 null → `setNullValue(key)`，即写 `""` + `CACHE_NULL_TTL = 2 分钟`；否则 `setWithLogicalExpire`。

### 2.4 空值缓存（穿透）与布隆过滤器

- 空值写 `""`，TTL 2 分钟（`CacheClient.java:154-156`）。
- 读侧判据写得很讲究：用 `StrUtil.isBlank(json)` 区分「空值缓存」和「key 不存在」，因为 `""` 既 blank 又非 null。
- 真实生产路径里，穿透的第一道防线其实是布隆过滤器（见第 3 节），`ShopServiceImpl#queryById` 先 `mightContain` 再查缓存。

### 2.5 一致性（写侧）

`ShopServiceImpl#update(Shop)`（`:84-96`）：`@Transactional` + **先改 DB（`updateById`）再删缓存（`DEL cache:shop:{id}`）**，标准 cache-aside 失效模式。注意没有延迟双删、没有 binlog 订阅。

### 2.6 锁的实现细节（Redisson 用法）

- 加锁：`lock.tryLock(0, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS)`，即 **waitTime=0（不等待，立即失败）、leaseTime=10s**（`CacheClient.java:166-173`）。因为 waitTime=0，Redisson 的看门狗续期**不会**生效（只有 leaseTime=-1 时才有 watchdog），锁最长持有 10 秒。
- 解锁：`if (lock.isHeldByCurrentThread()) lock.unlock();`（`:175-179`），用持有者判定避免 `IllegalMonitorStateException`。
- 中断处理：`InterruptedException` → `Thread.currentThread().interrupt()` 后返回 false。

### 2.7 缓存相关的死代码 / 未实现项

- `CacheClient#queryWithPassThrough` 定义了完整的 cache-aside + 空值缓存，**但没有任何调用点**。也就是说「Cache Aside 查询」这条经典路径在生产里并不生效，店铺走的是逻辑过期路径。
- `ShopServiceImpl:68` 定义了一个 `private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10)`，**从未使用**（真正的重建线程池在 `CacheClient:20`）。
- `ShopServiceImpl#saveShop2Redis(Long, Long)`（`:72-82`）是逻辑过期缓存预热方法，**无调用点**（还被 `Thread.sleep(200)` 污染）；实际预热靠测试类 `ShopCacheTest#testCacheAllShops`（`src/test/java/com/hmdp/ShopCacheTest.java:27-44`）。
- **随机 TTL（防雪崩）完全不存在**：全项目没有任何 `TTL + random` 逻辑；`setWithLogicalExpire` 写入时干脆不带 TTL（物理永不过期），所以雪崩风险被「永不过期」意外规避，但代价是 key 永不释放。

---

## 3. 布隆过滤器

**实现方：Redisson `RBloomFilter`**，不是 Guava、不是自研 bitset。

`src/main/java/com/hmdp/utils/ShopBloomFilter.java`：

- `@Component`，注入 `RedissonClient`；内部持有一个 `volatile boolean initialized` 标志（`:18`）。
- 底层 key：`RedisConstants.SHOP_BLOOM_KEY = "shop:bloom"`，通过 `redissonClient.getBloomFilter("shop:bloom")` 获取（`:73-75`）。数据实际存在 Redis 的 bitmap 里，由 Redisson 的 Lua 脚本做多次 hash 置位。
- **规格**（`init`，`:20-35`）：`filter.tryInit(RedisConstants.SHOP_BLOOM_EXPECTED_INSERTIONS, RedisConstants.SHOP_BLOOM_FALSE_PROBABILITY)`，即 **expectedInsertions = 100000、falseProbability = 0.01**（`RedisConstants.java:16-17`）。**位数组长度与 hash 函数个数代码里没有显式设置**，由 Redisson 依据这两个参数自行推导（Redisson 内部按 `-ln(p)/ln2` 量级计算迭代次数，属框架行为，非本项目代码）。
- **初始化时机与方式**：`ShopBloomFilterInitializer implements ApplicationRunner#run`（`src/main/java/com/hmdp/config/ShopBloomFilterInitializer.java:27-40`）在应用启动时 `shopService.lambdaQuery().select(Shop::getId).list()` 全量捞出所有店铺 id，逐个 `filter.add(shopId)`。初始化失败只 `log.warn` 并跳过，**不让应用起不来**。
- **判定**（`mightContain`，`:37-55`）：`shopId == null || <= 0` → 直接 false（`isValidShopId`，`:77-79`）；**未初始化 → 返回 true（当作没有过滤器，直接放行）**（`:41-43`）；`filter.isExists()` 为 false（Redis 里 key 没了）→ 把 `initialized` 置回 false 并放行（`:46-49`）；`catch` 任何异常 → `log.warn` 后**放行 true**（`:51-54`）。这是典型的 **fail-open** 策略：缓存/过滤器故障时退化为直查 DB，不为可用性以外的东西牺牲可用性。
- **增量维护**：`ShopBloomFilter#add(Long)`（`:57-71`）只在 `initialized` 为 true 且 `filter.isExists()` 时写入；调用点唯一 —— `ShopController#saveShop` 新增店铺后 `shopBloomFilter.add(shop.getId())`（`src/main/java/com/hmdp/controller/ShopController.java:42`）。
- **使用点**：`ShopServiceImpl#queryById` 第一行 `if (!shopBloomFilter.mightContain(id)) return Result.fail("店铺不存在！")`（`ShopServiceImpl.java:55-57`）。**目的**：拦住不存在的 id，避免打穿到 DB。
- **局限（代码事实）**：无删除能力（布隆过滤器固有）；`shop:bloom` **没有设置过期时间**；`ShopController` 没有删除店铺的接口，所以也不存在「删了店铺但过滤器仍返回存在」的路径。

---

## 4. 分布式锁

### 4.1 `SimpleRedisLock`（自研，**当前为死代码且存在文件名 bug**）

`src/main/java/com/hmdp/utils/SimpleRedisLock.java`（实现 `ILock`）：

- 锁 key：`"lock" + name`（`KEY_PREFIX = "lock"`，`:17,41`）。
- **线程标识**：`ID_PREFIX = UUID.randomUUID().toString(true) + "-"` 是 **static final，类加载时生成一次**，再拼 `Thread.currentThread().getId()`（`:18,38,51`）。即「JVM 实例 UUID + 线程 id」，能区分同 JVM 内不同线程、也能区分不同 JVM。
- **加锁**：`stringRedisTemplate.opsForValue().setIfAbsent("lock"+name, threadId, timeoutSec, TimeUnit.SECONDS)`（`:40-42`）—— 底层即 `SET key value NX PX`，返回 `Boolean.TRUE.equals(success)`。
- **解锁**：Lua 脚本比对后删除，保证「判断 + 删除」原子（`:46-53`）。
  - ⚠️ **实际文件名不匹配**：代码加载 `new ClassPathResource("unlock.lua")`（`:26`，全小写），但 `src/main/resources/` 下真实文件是 **`unLock.lua`**（大写 L，实测 `ls` 确认）。Linux 文件系统区分大小写，因此一旦该类被加载，静态初始化块会因资源找不到而抛异常。这是「注释/代码与磁盘不一致」的实锤缺陷。
  - `src/main/resources/unLock.lua:1-6` 内容正确：`if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end return 0`。
- **无人使用**：全项目 grep `SimpleRedisLock` / `ILock` 只有定义处，**没有任何实例化或注入点**。`Object` 语义上也未实现可重入与自动续期。旧的非原子解锁实现被注释保留在 `:55-65`（含 `DefaultRedisList` 这个未使用的 import，`:7`）。

### 4.2 Redisson 锁的真实使用点

只有 **缓存重建** 一处：

- `CacheClient` 注入 `RedissonClient`（`:23,25-28`），在 `queryWithLogicalExpire`（`:85`）与 `queryWithMutexAndLogicalExpire`（`:106`）里 `redissonClient.getLock(RedisConstants.LOCK_SHOP_KEY + key)`，参数 `tryLock(0, 10, SECONDS)`（`:168`）。
- `RedissonConfig`（`src/main/java/com/hmdp/config/RedissonConfig.java:12-18`）：`config.useSingleServer().setAddress("redis://127.0.0.1:6379")`，**单机模式、地址硬编码**（未读取 `application.yaml` 的 `spring.data.redis.*`）。
- 秒杀、下单、点赞、关注**都没有用分布式锁**。

---

## 5. 全局唯一 ID（`RedisIdWorker`）

`src/main/java/com/hmdp/utils/RedisIdWorker.java`：

- **位布局**：`timestamp << COUNT_BITS | count`，`COUNT_BITS = 32`（`:17,33`）。即 **高 32 位秒级时间戳（相对纪元） + 低 32 位日内序列号**。
- **时间戳**：`BEGIN_TIMESTAMP = 1640995200L`（`:14`，即 2022-01-01T00:00:00Z），`timestamp = nowSecond - BEGIN_TIMESTAMP`（`:23-25`）。`nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)`（`:24`）—— 注意这是把**本地墙上时间当 UTC 解释**，在 UTC+8 会比真实 epoch 大 8 小时；对唯一性无影响，只是起始纪元语义偏移。
- **序列号**：按 **天** 分片，key = `"icr:" + keyPrefix + ":" + yyyy:MM:dd`（`DateTimeFormatter.ofPattern("yyyy:MM:dd")`，`:28-29`），用 `opsForValue().increment(...)` 原子自增。即每天每业务前缀从 1 重新计数，单日单前缀上限 2^32。
  - 实际调用只见到 `redisIdWorker.nextId("order")`（`VoucherOrderServiceImpl.java:129`），所以真实运行时 key 形如 `icr:order:2025:09:16`。
  - 该计数器 key **没有设置 TTL**，历史日期 key 会永久残留。
  - `increment` 返回值未做 null 判断，返回 null 时第 33 行拆箱会 NPE（正常不会发生）。
- 订单实体用 `@TableId(value = "id", type = IdType.INPUT)` 接收这个外部 ID（`src/main/java/com/hmdp/entity/VoucherOrder.java:33-34`）。

---

## 6. 认证授权

### 6.1 结论先行：**不是 JWT，也没有 refresh token**

- 令牌是 `UUID.randomUUID().toString(true)`（去掉横线）的**不透明随机串**，本身不携带任何信息（`UserServiceImpl.java:128-133`）。
- 用户身份只存在于 Redis：`login:token:{token}` → `UserDTO` 的 JSON，TTL 30 分钟（`LOGIN_USER_KEY`/`LOGIN_USER_TTL_MINUTES`，`RedisConstants.java:7-9`）。
- **没有 access/refresh 双 token**，没有签名校验，没有过期时间字段。续期靠服务端滑动 TTL。
- 优点（类注释自陈，`TokenInterceptor.java:19-23`）：登出/封号只需删 key 即刻生效，不需要黑名单，也不用等服务端签名过期。

### 6.2 短信验证码登录流程

1. `UserController#sendCode`，`POST /user/code?phone=`，标 `@PublicApi`（`src/main/java/com/hmdp/controller/UserController.java:32-36`）。
2. `UserServiceImpl#sendCode`（`:39-49`）：`RegexUtils.isPhoneInvalid(phone)` 校验 → `RandomUtil.randomNumbers(6)` 生成 6 位数字 → `SET login:code:{phone} = code EX 2min`（`LOGIN_CODE_TTL_MINUTES = 2L`）。
   - 注释明确「验证码只写 Redis，不要打日志：日志能读的人就能登录任意手机号」（`:45`）。**副作用**：代码里既没有接短信网关、也没有在响应里返回验证码，所以实际上只能读 Redis 才能完成登录（测试类即这样做：`VoucherOrderTest` 中 `stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone)`）。
   - **没有对发送验证码做限流**（无次数/频次/图形码）。
3. `UserController#login`，`POST /user/login`，`@PublicApi`（`:41-45`）→ `UserServiceImpl#login`（`:52-71`）：校验手机号格式 → `GET login:code:{phone}` 与入参 `code` 字符串比对（`:59-62`）→ `query().eq("phone", phone).one()`，不存在则 `createUserWithPhone`（昵称 `user_` + `RandomUtil.randomString(10)`，`USER_NICK_NAME_PREFIX = "user_"`，`SystemConstants.java:4`）→ `BeanUtil.copyProperties(user, UserDTO.class)` 只保留 `id/nickName/icon` → `createLoginToken(userDTO)` 返回 token 字符串。
   - ⚠️ **登录成功后验证码没有删除**（无 `delete(LOGIN_CODE_KEY + phone)`），2 分钟窗口内同一个验证码可重复使用。
4. `UserController#logout`，`POST /user/logout`，**标了 `@PublicApi`**（`:50-54`）→ `UserServiceImpl#logout(token)`（`:74-81`）：`TokenInterceptor.resolveToken(token)` 归一化后 `DEL login:token:{token}`。

### 6.3 拦截器链（`src/main/java/com/hmdp/config/MvcConfig.java:19-26`）

```
对所有路径 /**：
  order(0) TokenInterceptor  —— 解析令牌、续期、写 UserHolder（不拦截）
  order(1) LoginInterceptor  —— 校验 UserHolder，未登录 401（拦截）
```
两个拦截器都是 `new` 出来的（TokenInterceptor 手工传入 `StringRedisTemplate`），非 Spring Bean。

**`TokenInterceptor`**（`src/main/java/com/hmdp/interceptor/TokenInterceptor.java`）：
- Header：`authorization`；`resolveToken` 兼容标准 `Bearer <token>`（大小写不敏感，`startWithIgnoreCase`）和裸 token（`:30-31,68-77`）。该方法为 `public static`，因为登出要复用同一套归一化逻辑。
- **续期机制**：`stringRedisTemplate.opsForValue().getAndExpire(key, Duration.ofMinutes(30))`（`:48-49`）—— 用 Redis **GETEX** 一次往返完成「取值 + 续满 30 分钟」，注释点出这样也避免了「取值和续期之间令牌刚好过期」的窗口。**滑动过期，无绝对上限。**
- 空/无效令牌**静默放行**，是否拦截交给 `LoginInterceptor`（`:25`、`:50-52`）。
- `preHandle` 成功解析后 `UserHolder.saveUser(JSONUtil.toBean(json, UserDTO.class))`（`:54`）；`afterCompletion` 无条件 `UserHolder.removeUser()`（`:58-62`）。

**`LoginInterceptor`**（`src/main/java/com/hmdp/interceptor/LoginInterceptor.java`）：
- **默认全接口需登录**，白名单是**方法级注解** `@PublicApi`（`com.hmdp.annotation.PublicApi`，`@Target(METHOD) @Retention(RUNTIME)`），通过 `handlerMethod.hasMethodAnnotation(PublicApi.class)` 判断（`:33-36`）。类注释给出的理由很实在：按方法而不是 URL 判断，既不会「`/shop/{id}` 公开导致 `POST /shop` 也被前缀带走」，也不存在「同一前缀下有读有写分不开」。
- `request.getDispatcherType() == DispatcherType.ERROR` 直接放行（`:30-32`），避免容器错误转发到 `/error` 时游客看到 401 而不是错误页。
- 拿不到 `HandlerMethod`（静态资源等）→ **按「要登录」处理**（`instanceof` 不成立即落到 `UserHolder.getUser() == null` 分支），注释「宁可拦错，也不放过」（`:22-23`）。

**`UserHolder`**（`src/main/java/com/hmdp/utils/UserHolder.java`）：`static final ThreadLocal<UserDTO>`，仅有 `saveUser/getUser/removeUser` 三个静态方法。

### 6.4 `/agent` SSE 端点的登录态处理

`AgentChatController#chat`，`POST /agent/chat`，`produces = MediaType.TEXT_EVENT_STREAM_VALUE`（`src/main/java/com/hmdp/controller/AgentChatController.java:97-110`）：

- 该接口**没有** `@PublicApi` → 必须登录，由同一条拦截器链保护；拦截器在**请求线程**上运行，SSE 的 `SseEmitter` 只影响后续响应处理，不影响 `preHandle`。
- 关键处理：`UserDTO user = UserHolder.getUser(); final Long userId = user.getId();`（`:99-101`），注释写明「**异步线程拿不到 UserHolder，先取出 userId**」。随后把 `userId` 以 final 局部变量捕获进 `agentChatExecutor.execute(() -> streamChat(userId, request, emitter))`（`:108`）。即**绝不在工作线程里读 ThreadLocal**。
- 其他 SSE 工程细节：`response.setHeader("X-Accel-Buffering", "no")` + `Cache-Control: no-cache`（`:103-104`）；`new SseEmitter(properties.getLlm().getTimeoutMs() + 30_000L)`（`:106`）；先 `execute` 再 `return emitter`（`:107-109`，注释说明必须先返回让 Spring 初始化 handler，否则事件会堆在 `earlySendAttempts` 里一次性刷出，流式退化成「憋完再吐」）；`sendEvent` 吞掉 `IOException/IllegalStateException` 只记 debug（`:142-151`）；`writeJson` 失败降级为 `"[]"`（`:153-160`）。
- ThreadLocal 清理：代码里**没有**为异步派发做额外保护，仍依赖 `TokenInterceptor#afterCompletion` 的 `removeUser()`；是否存在异步派发线程不一致导致残留，代码中没有显式防护（此点属代码事实，非实测结论）。

---

## 7. 笔记 / 博客（Blog）

### 7.1 点赞（Redis ZSet，不是 Set）

`BlogServiceImpl#updateLike(Long id)`（`src/main/java/com/hmdp/service/impl/BlogServiceImpl.java:83-108`）：

- Key：`BLOG_LIKED_KEY + id` = **`blog:liked:{blogId}`**，类型 **ZSet**，member = `userId`，**score = `System.currentTimeMillis()`**（点赞时间）。
- 判重：`opsForZSet().score(key, userId.toString())` 是否为 null（`:89`）—— 用 `score` 而不是 `sismember`，因为 score 同时充当时间戳。
- 点赞：`update().setSql("liked = liked + 1").eq("id", id).update()` 成功后才 `ZADD`（`:93-97`）；取消：`liked = liked - 1` 后 `ZREM`（`:101-105`）。
- ⚠️ **DB 与 Redis 不在同一事务**：`updateLike` 没有 `@Transactional`，DB 成功后 Redis 失败会导致计数与集合不一致（无补偿）。
- `blog:liked:{id}` **没有设置 TTL**。

**点赞用户 Top5**（`queryBlogLikes`，`:110-132`）：`opsForZSet().range(key, 0, 4)` 取**最早点赞的 5 个人**（按 score 升序），再用 MyBatis-Plus `query().in("id", ids).last("order by field(id," + idStr + ")")` 保持 Redis 返回顺序（`:114-124`）。注释里贴了对应的 SQL 形态。

### 7.2 「排行榜」的真相

- **点赞数排行榜查的是数据库列，不是 Redis ZSet**：`queryHotBlog(Integer current)`（`:51-65`）= `query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE))`，页大小 `MAX_PAGE_SIZE = 10`。**不存在**基于 ZSet 的 Redis 排行榜。
- `queryBlogByShopId(Long shopId)`（`:67-81`）同样 `eq("shop_id", shopId).orderByDesc("liked").list()`，无分页。
- 每条记录回填作者昵称/头像（`queryBlogUser`，`:258-267`，作者查不到时留空不抛 500）和当前用户是否点赞（`isBlogLiked`，`:243-256`，未登录直接跳过）。

### 7.3 关注流（收件箱）——**写扩散 push，不是 ZSet 交集**

**发布侧** `saveBlog(Blog blog)`（`:134-157`）：
1. `UserHolder.getUser()` 取作者 → `save(blog)`；
2. `followService.query().eq("follow_user_id", user.getId()).list()` 查**所有粉丝**（注意语义：`follow_user_id = 作者`，返回的行的 `userId` 才是粉丝）；
3. 逐个粉丝 `ZADD feed:{粉丝userId} blogId System.currentTimeMillis()`（Key 前缀 `FEED_KEY = "feed:"`，`:152-153`）。
   - 大 V 发帖 = 全量粉丝写 Redis，是典型的写扩散代价，**无异步、无批量 pipeline、无写扩散上限**。
   - 注释（`:144`）说明知识库正文是离线静态内容，发博客不触发重新入库。

**拉取侧** `quertBlogOfFollow(Long max, Integer offset)`（`:159-205`，方法名拼写错误但保留）：
- Key `feed:{当前userId}`；核心命令 `opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2)`（`:165-166`）—— **ZREVRANGEBYSCORE，每页固定取 2 条**（硬编码，不是常量）。
- **滚动分页算法**（`:171-186`）：遍历结果，`time == minTime` 则 `os++`，否则 `minTime = time; os = 1`；循环结束后 `minTime` 最后一条（最小）分数，`os` 是「与最小分数相同的条数」，作为下一页的 `offset`，避免同分数（同一毫秒）被跳过或重复。
- 回填：`query().in("id", ids).last("order by field(id," + idStr + ")")` 保持时间序（`:189-190`），再逐条 `queryBlogUser` + `isBlogLiked`（`:192-197）—— **N+1 查询，没有批量预取**。
- 返回 `ScrollResult{list, minTime, offset}`（`src/main/java/com/hmdp/dto/ScrollResult.java:7-12`）。
- 入口 `GET /blog/of/follow?lastId=&offset=`（`BlogController.java:102-108`），**未标 `@PublicApi`**，必须登录。

### 7.4 笔记详情的关注状态

`queryBlogById`（`:207-220`）额外调 `queryFollowStatus(blog)`（`:222-241`）：先置 `isFollow=false / isSelf=false`，未登录直接返回；否则比较 `userId == authorId` 置 `isSelf`，非本人再 `count()` 查 `tb_follow` —— **走 DB，不查 Redis Set**。

---

## 8. 关注 / 取关 / 共同关注

`src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`：

- **Key**：`"follows:" + userId`（字面量硬编码 **两次** 出现，`:36` 和 `:74`，未提取常量），类型 **Redis Set**，member = 被关注者 id。**无 TTL**。
- **关注** `follow(followUserId, isFollow=true)`（`:33-47`）：`save(new Follow())` 写 `tb_follow` 成功后 `SADD follows:{userId} followUserId`。
- **取关**（`:48-57`）：`remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId))` 成功后 `SREM`。⚠️ `remove` 返回的 `isSuccess` 是 MyBatis-Plus 的「是否执行成功」而非「删了几行」，所以取关一个本来就没关注的用户也会 `SREM`（无害但语义不精确）。
- ⚠️ **DB 与 Redis 不在同一事务**（方法无 `@Transactional`），`save` 成功、`SADD` 失败会造成两者不一致，**没有补偿、没有重建任务**。`tb_follow` 表也**没有 `(user_id, follow_user_id)` 唯一索引**（`db/hmdp.sql:72-80` 只有主键），重复关注会产生重复行。
- **是否关注** `isFollow(followUserId)`（`:61-68`）：直接 `query().eq("user_id",...).eq("follow_user_id",...).count()` **查 DB**，没有利用 Redis Set。
- **共同关注** `followCommons(Long id)`（`:70-92`）：`opsForSet().intersect("follows:" + userId, "follows:" + id)` → 解析成 `List<Long>` → `userService.listByIds(ids)` → `BeanUtil.copyProperties(..., UserDTO.class)`。这是全项目**唯一**使用 Redis Set 交集的地方。
  - 入口 `GET /follow/common/{id}`（`FollowController.java:42-45`），需登录。
- 其他入口：`PUT /follow/{id}/{isFollow}`（关注/取关）、`GET /follow/or/not/{id}`（是否关注）—— 均在 `FollowController.java:22-35`，都需登录。

**修正一个常见说法**：关注流的组装**不是** ZSet intersect，而是「发布时写扩散到每个粉丝的 `feed:{userId}` ZSet + 拉取时 `ZREVRANGEBYSCORE` 滚动分页」；ZSet 交集在本项目中**不存在**，Set 交集只服务于「共同关注」。

---

## 9. 店铺查询 / GEO

`ShopServiceImpl#queryShopByType(Integer typeId, Integer current, Double x, Double y)`（`src/main/java/com/hmdp/service/impl/ShopServiceImpl.java:98-150`）：

- **无坐标分支**（`:101-107`）：走 DB，`eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE))`，页大小 `DEFAULT_PAGE_SIZE = 5`。
- **有坐标分支**：
  1. 分页参数 `from = (current-1)*5`、`end = current*5`（硬编码 `SystemConstants.DEFAULT_PAGE_SIZE`，`:109-110`）。
  2. Key：`SHOP_GEO_KEY + typeId` = **`shop:geo:{typeId}`**（按店铺类型分片，`:113`）。
  3. 调用的 Redis 命令（Spring Data Redis API）：`stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y), new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end))`（`:115-121`）—— 底层即 **`GEOSEARCH key FROMMEMBER/FROMLONLAT ... BYRADIUS 5000 m ASC WITHCOORD/WITHDIST COUNT end`**，半径**硬编码 5000 米**，`includeDistance()` 带回距离，`limit(end)` 多取以便内存分页。
  4. **分页实现是「多取 + 内存 skip」**：`if (list.size() <= from) return emptyList()`（`:127-129`），再 `list.stream().skip(from).forEach(...)` 收集 id 和 `Distance`（`:133-140`）。没有用 Redis 原生 `COUNT offset count` 分页。
  5. 回表：`StrUtil.join(",", ids)` 后 `query().in("id", ids).last("order by field(id," + idStr + ")")`（`:142-144`）—— 用 `ORDER BY FIELD` 还原 Redis 的距离序（因为 `IN` 不保证顺序）。
  6. 距离回填：`shop.setDistance(distanceMap.get(shop.getId().toString()).getValue())`（`:146`），`getValue()` 是**米**（`Distance` 默认 `Metrics.NEUTRAL`）。
- **GEO 数据的写入不在生产代码里**：全项目 grep `opsForGeo().add` 只命中测试类 `src/test/java/com/hmdp/HmDianPingApplicationTests.java:89`（`loadShopDate()`：按 `typeId` 分组 → `RedisGeoCommands.GeoLocation<>(shopIdStr, new Point(x, y))` → 批量 `add(key, locations)`）。**生产代码没有任何店铺坐标的增量同步（新增/修改店铺不会 GEOADD）**，也没有 `GEOADD` 的补偿/重建机制。
- 入口：`GET /shop/of/type?typeId=&current=&x=&y=`，`@PublicApi`（`ShopController.java:65-74`）；按名称查询 `GET /shop/of/name` 用 `like` + `MAX_PAGE_SIZE=10` 分页（`:82-94`）。

---

## 10. 用户签到（Redis Bitmap）

全部在 `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`：

- **Key**：`USER_SIGN_KEY + userId + ":yyyyMM"`，即 **`sign:{userId}:{yyyyMM}`**（`RedisConstants.USER_SIGN_KEY = "sign:"`，`UserServiceImpl.java:87-88`）。按**月**分片，**位下标 = 当月第几天 - 1**（第 1 天 → bit 0）。
- **签到** `sign()`（`:83-92`）：`stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true)` → 底层 **`SETBIT key offset 1`**。天然幂等，重复签到无副作用。入口 `POST /user/sign`（需登录，`UserController.java:99-102`）。
- **连续签到统计** `signCount()`（`:94-123`）：
  1. 计算当月已过天数 `dayOfMonth`；
  2. `stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0))`（`:101-104`）→ 底层 **`BITFIELD key GET u{dayOfMonth} 0`**，一次取出「从本月 1 号到今天的全部位」组成的一个无符号整数；
  3. null / 空 / 0 → 返回 0；
  4. **从最低位（今天）往上数连续的 1**：`while (true) { if ((num & 1) == 0) break; count++; num >>>= 1; }`（`:114-121`）—— 用**无符号右移**（`>>>=`）逐位检查，得到「截至今天的连续签到天数」。入口 `GET /user/sign/count`。
- 该 key **没有设置 TTL**（按月分片但不会自动清理）。
- 存在 `tb_sign` 表（`db/hmdp.sql:158`），但**签到逻辑不写数据库**，Redis 是唯一存储。

---

## 11. 其他工程细节

### 11.1 限流 / 熔断 / AOP

- **限流：完全没有。** 全项目 grep `RateLimit|限流|sentinel` 在业务代码中零命中（唯一命中在 RAG 模块关于 Milvus flush 限流的注释）。发验证码、秒杀、点赞、发博客均无任何频次控制。
- **自定义注解：只有一个** —— `com.hmdp.annotation.PublicApi`（`@Target(METHOD)`、`@Retention(RUNTIME)`、`@Documented`），作为「默认全需登录」白名单。用法见 `UserController`、`ShopController`、`BlogController`、`VoucherController`、`ShopTypeController`。
- **AOP 切面：一个都没有。** `pom.xml:84` 引入了 `aspectjweaver`，`HmDianPingApplication.java:10` 开了 `@EnableAspectJAutoProxy(exposeProxy = true)`，但 grep `@Aspect / @Around / @Before / AopContext` **全部零命中**——`exposeProxy` 目前无实际消费者（传统上用于自调用拿代理，本项目改用 `TransactionTemplate` 解决同类问题）。
- 也没用 Spring Cache 抽象（`@Cacheable/@CacheEvict/CacheManager` 零命中），缓存全部手写。

### 11.2 线程池

| 线程池 | 参数 | 位置 | 用途 |
|---|---|---|---|
| `CacheClient.CACHE_REBUILD_EXECUTOR` | `Executors.newFixedThreadPool(10)` | `CacheClient.java:20` | 逻辑过期后的异步缓存重建（**生产实际使用**） |
| `agentChatExecutor` | `ThreadPoolTaskExecutor`：core 4 / max 16 / queue 64 / 前缀 `agent-chat-` | `src/main/java/com/hmdp/config/AgentChatExecutorConfig.java:21-30` | SSE 流式回答（AI 部分，非本文重点） |
| `ShopServiceImpl.CACHE_REBUILD_EXECUTOR` | `newFixedThreadPool(10)` | `ShopServiceImpl.java:68` | **死代码，无引用** |

无 `@Async` / `@EnableAsync`（grep 零命中）。

### 11.3 事务

- `@Transactional`：仅 3 处 —— `VoucherServiceImpl#addVoucher`（`VoucherServiceImpl.java:36`）、`ShopServiceImpl#update`（`ShopServiceImpl.java:85`）、`AgentChatServiceImpl:176`（`rollbackFor = Exception.class`，AI 部分）。
- 编程式事务：`VoucherOrderServiceImpl` 注入 `TransactionTemplate`，在 `completeVoucherOrder` 里 `transactionTemplate.executeWithoutResult(status -> createVoucherOrder(order))`（`VoucherOrderServiceImpl.java:88,343`）—— 目的是让 `DuplicateKeyException` **在事务边界之外**被捕获，避免 `UnexpectedRollbackException`（注释 `:301-309, 344-346`）。
- **未纳入事务的写操作**（一致性靠约定）：`BlogServiceImpl#updateLike`（DB 计数 + Redis ZSet）、`FollowServiceImpl#follow`（DB + Redis Set）、`VoucherServiceImpl#addVoucher`（Redis 写在事务内，若之后回滚会残留库存 key）。

### 11.4 异常处理

- `src/main/java/com/hmdp/config/WebExceptionAdvice.java`：`@RestControllerAdvice`，只处理 `RuntimeException.class` → `log.error` + `Result.fail("服务器异常")`（`:11-15`）。**没有**针对 `BusinessException`/参数校验/认证失败的处理器；401 是拦截器直接 `response.setStatus(401)` 且**响应体为空**（`LoginInterceptor.java:37-40`）。
- 没有全局的 `@Valid` 参数校验（DTO 上无 `@NotNull` 等注解）。
- `Result` 统一响应体：`{success, errorMsg, data, total}`（`src/main/java/com/hmdp/dto/Result.java:12-30`）。

### 11.5 MyBatis-Plus 使用

- `MybatisConfig`（`src/main/java/com/hmdp/config/MybatisConfig.java:12-16`）：注册 `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)`（全项目唯一一个分页插件配置）。
- 全部 Service 继承 `ServiceImpl<Mapper, Entity>`；`@MapperScan("com.hmdp.mapper")`（`HmDianPingApplication.java:11`）。
- 典型用法：`lambdaQuery().eq(...)`（`ShopBloomFilterInitializer.java:29`、`ShopKnowledgeServiceImpl.java:93`）、`query().eq(...).page(new Page<>(...))`、`update().setSql("liked=liked+1").eq("id", id).update()`、`StrUtil.join` + `.last("order by field(id, ...)")` 保持 Redis 顺序。
- 自定义 XML Mapper 仅 `VoucherMapper.xml`（`:6-11`，查 `tb_voucher WHERE shop_id=? AND status=1`）。
- 主键策略：`IdType.AUTO`（Voucher/Blog）、**`IdType.INPUT`（VoucherOrder，由 RedisIdWorker 赋值）**。
- **未使用逻辑删除、未使用乐观锁插件、未使用多租户/数据权限插件。**

### 11.6 其他

- **定时任务**：`@EnableScheduling`（`src/main/java/com/hmdp/config/SchedulingConfig.java:6-8`）；实际只有 2 个 `@Scheduled`：`VoucherOrderServiceImpl#republishUnconfirmedOrders`（`fixedDelay=5000`）、`ShopKnowledgeServiceImpl#indexChangedShops`（`fixedDelay=${hmdp.agent.scan-interval-ms:15000}`，`initialDelay=${...:20000}`）。**都没有分布式锁**。
- **`ShopKnowledgeServiceImpl` 的非 AI 骨架**（与本次任务相关的那一半）：`@Scheduled` 扫描 `tb_shop_knowledge`，用 `DigestUtil.md5Hex` 算正文指纹与库里的 `content_hash` 比对，不一致才重新入库；失败不更新指纹 → 下一轮自动重试；全量重建手段是 `UPDATE tb_shop_knowledge SET content_hash = NULL`（`src/main/java/com/hmdp/service/impl/ShopKnowledgeServiceImpl.java:62-89, 117-129`）。这是「指纹驱动的幂等扫描」模式，不涉及限流/事务。
- **店铺分类缓存**（`ShopTypeServiceImpl#querySort`，`src/main/java/com/hmdp/service/impl/ShopTypeServiceImpl.java:28-46`）：用 **Redis List**（`shop_type:`）整体缓存，读 `opsForList().range(key, 0, -1)`，未命中则 `query().orderByAsc("sort").list()` 后逐条 `rightPush`。⚠️ 两个弱点：(1) **完全没有设置 TTL**；(2) 常量 `RedisConstants.SHOP_TYPE_LONG = 10L`（`RedisConstants.java:41`）定义了却**从未被引用**，看起来是打算做 TTL 但没接上；也没有缓存更新/失效逻辑。
- **Redis 连接池**（`application.yaml:31-36`）：Lettuce `max-active 30 / max-idle 15 / min-idle 5 / time-between-eviction-runs 10s`，`commons-pool2` 已引入（`pom.xml:43`）。
- **Jackson**：`default-property-inclusion: non_null`（`application.yaml:38`）。
- **上传**（`src/main/java/com/hmdp/controller/UploadController.java`）：写本地 nginx 静态根（`hmdp.upload.root`），返回 `/imgs/...` 公开路径；删除时按注释「只允许删 imgs 目录里的普通文件」，既挡 `../` 路径穿越也挡同根下其它静态文件（`:56-60`）。
- **测试基建**：`VoucherOrderControllerTest`（`src/test/java/com/hmdp/VoucherOrderControllerTest.java`）是**手动执行的秒杀压测工具**（`@Disabled`），会真实登录 1000 个用户、往 Redis 写 1000 个 `login:token:*`（TTL 30 分钟）、生成 `target/tokens.txt`；`ShopCacheTest` 负责把全量店铺按逻辑过期写入 Redis 做预热。
- **配置硬编码**：`RedissonConfig` 的 `redis://127.0.0.1:6379` 未走配置文件；`application.yaml` 里 MySQL 口令为明文。

---

## 12. 结论：简历可以怎么写、不能怎么写

**可以如实写的（有代码支撑）**

- 基于 **Redis + Lua 原子预占 + RabbitMQ 异步落库** 的秒杀链路：Lua 单脚本完成「判库存 / 一人一单 / 扣减 / 记状态 / 记待投递」，返回码 `-1/1/2/0` 分流四种结果。
- **可靠性闭环**：发布确认（correlated + returns + mandatory）+ 手动 ACK + TTL 延迟队列（15s × 20 次）+ 死信队列 + ZSet「待投递」补发 + `abandonSeckill.lua` 幂等补偿（`SREM` 守卫的库存回加）。
- **三级幂等**：Redis Set 一人一单 → 应用层 `getById`/`isAbandoned` 预检 → DB `uk_user_voucher` 唯一索引兜底；并刻意让 `DuplicateKeyException` 逃出事务边界以规避 `UnexpectedRollbackException`。
- **缓存高可用**：Redisson `RBloomFilter`（10 万容量 / 1% 误判）防穿透 + 空值缓存（2min）+ 「逻辑过期 + Redisson 互斥锁 + 10 线程池异步重建」防击穿；写侧 `@Transactional` 先改库再删缓存。
- **Redis 实战数据结构**：ZSet 做点赞与写扩散收件箱（含 `ZREVRANGEBYSCORE` 滚动分页的同分 offset 处理）、Set 做共同关注交集、Bitmap + `BITFIELD GET uN 0` 做连续签到、GEO（`GEOSEARCH` + `WITHDIST` + `COUNT`）做附近店铺、Hash 做订单状态机。
- **认证**：默认全接口鉴权 + `@PublicApi` 方法级白名单、双拦截器（解析/续期 vs 拦截）、Redis `GETEX` 单次往返滑动续期、`ThreadLocal` UserHolder 且在 SSE 异步线程前显式提取 `userId`。
- **全局 ID**：`RedisIdWorker` 32 位秒级时间戳（纪元 1640995200）+ 32 位日内序列（`icr:{prefix}:{yyyy:MM:dd}` 原子 INCR）的 Snowflake 式布局。

**不能写 / 需要修正的（会被追问穿）**

1. **没有 JWT，更没有 access+refresh 双 token** —— 是不透明 UUID + Redis 滑动 30 分钟。
2. **秒杀没有用 Redisson 分布式锁**，用的是 Lua `SISMEMBER` + 唯一索引；`SimpleRedisLock` 是**从未被调用的死代码，且 `unlock.lua` 文件名大小写与磁盘上的 `unLock.lua` 不一致，一加载就会失败**。
3. **没有随机 TTL，缓存雪崩没有做**；逻辑过期路径干脆不设物理 TTL。
4. **没有限流、没有 AOP 切面、没有 Sentinel/熔断**；`aspectjweaver` 与 `exposeProxy=true` 目前是空转的。
5. **没有 Redis 点赞排行榜**（热门榜查的是 DB 的 `liked` 列）；**没有 ZSet 交集组装关注流**（是写扩散 push，交集只用于共同关注）。
6. **没有支付/退款/核销状态机**（`VoucherOrder` 实体已把 `pay_type/status/pay_time/use_time/refund_time` 全部删除，注释说明本项目没有那条流程）。
7. 秒杀入口**不校验券的上下架与时间窗**（代码注释自己承认接口可被直接调用）；DB 扣库存**没有 `stock > 0` 条件**，`tb_voucher.stock` 也未加无符号约束。
8. GEO 数据**只由测试类导入**，生产代码没有店铺坐标的增量同步。
9. `CacheClient#queryWithPassThrough`、`ShopServiceImpl#saveShop2Redis`、`ShopServiceImpl.CACHE_REBUILD_EXECUTOR` 均为无调用点的死代码 —— 不要把它们说成线上生效的能力。
