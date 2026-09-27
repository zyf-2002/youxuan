package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.QueueConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_UNDELIVERED_KEY;

/**
 * <p>
 * 秒杀链路的分工：
 * </p>
 * <ul>
 *   <li><b>Redis</b>：抢购闸门（库存够不够、这人买过没）+ 前端要查的订单状态 + 一个「待投递」集合；</li>
 *   <li><b>MQ</b>：把每一单可靠地送到数据库，重试和放弃都由它负责
 *       （延迟重投、重投上限、死信队列）；</li>
 *   <li><b>数据库</b>：只由消费者写，靠主键和 {@code uk_user_voucher} 唯一索引保证幂等。</li>
 * </ul>
 * <p>
 * 和之前的区别：应用侧<b>没有第二个写数据库的路径</b>了。原来那个每 10 秒扫一遍、
 * 自己补写数据库的定时任务换成了 {@link #republishUnconfirmedOrders()}——
 * 它只补发消息，不碰数据库。
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 补发任务一轮最多处理多少条 */
    private static final int UNDELIVERED_SCAN_BATCH_SIZE = 50;
    /** 超过这个时间还没收到发布确认，才认为这条消息没交出去，需要补发 */
    private static final long UNDELIVERED_MIN_AGE_MS = 5000L;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> ABANDON_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        ABANDON_SCRIPT = new DefaultRedisScript<>();
        ABANDON_SCRIPT.setLocation(new ClassPathResource("abandonSeckill.lua"));
        ABANDON_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IVoucherService voucherService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 发布确认：broker 明确回 ack 才算消息交出去了，这时才删 Redis 里的「待投递」标记。
     * 没确认、或者消息被退回（路由不到队列），标记就留着，等补发任务重发。
     */
    @PostConstruct
    public void initPublisherCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String orderId = correlationData == null ? null : correlationData.getId();
            // 被退回过的消息 broker 也会回 ack（它确实收下了，只是投不出去），这种不算成功
            boolean returned = correlationData != null && correlationData.getReturned() != null;
            if (ack && !returned) {
                if (orderId != null) {
                    stringRedisTemplate.opsForZSet().remove(SECKILL_UNDELIVERED_KEY, orderId);
                }
                return;
            }
            log.error("秒杀消息未被 broker 接收，等待补发，orderId={}, ack={}, cause={}", orderId, ack, cause);
        });

        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "秒杀消息无法路由到任何队列，等待补发：exchange={}, routingKey={}, body={}",
                returned.getExchange(), returned.getRoutingKey(), new String(returned.getMessage().getBody())));
    }

    /**
     * 用户点击秒杀时进入这里。
     * Lua 负责原子判断资格、扣 Redis 库存、记录用户、写订单状态、记一笔待投递。
     * <p>
     * 这里<b>不校验</b>券的上下架和时间窗：能不能买只看 Redis 里有没有库存 key。
     * 前端会自己按时间把按钮置灰，但接口是可以被直接调用的。
     * </p>
     * <p>
     * 方法返回只代表「抢到资格并进入后台创建订单流程」，<b>不代表 MySQL 订单已经写完</b>——
     * 真正的落库结果要轮询 {@link #querySeckillOrderStatus(Long)}。
     * </p>
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        long now = System.currentTimeMillis();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        SECKILL_ORDER_STATUS_KEY + orderId,
                        SECKILL_UNDELIVERED_KEY
                ),
                userId.toString(),
                voucherId.toString(),
                String.valueOf(orderId),
                String.valueOf(now)
        );

        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            return Result.fail(getSeckillFailMessage(r));
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        sendOrderMessage(order);

        return Result.ok(String.valueOf(orderId));
    }

    /**
     * 查询秒杀订单的处理结果，前端抢到资格后轮询它。
     * <p>
     * <b>先查数据库</b>：订单落库了就是最终结果，Redis 里那份状态删没删都不影响它。
     * 数据库没有再看 Redis：有状态就是处理中（或者已经作废为失败），两边都没有就是查不到了。
     * </p>
     */
    @Override
    public Result querySeckillOrderStatus(Long orderId) {
        Long userId = UserHolder.getUser().getId();

        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!userId.equals(order.getUserId())) {
                return Result.fail("无权查询该订单");
            }
            return Result.ok(buildStatusResult(orderId, "SUCCESS", "下单成功"));
        }

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(SECKILL_ORDER_STATUS_KEY + orderId);
        if (entries == null || entries.isEmpty()) {
            return Result.ok(buildStatusResult(orderId, "UNKNOWN", "订单状态已过期或不存在"));
        }

        Object owner = entries.get("userId");
        if (owner != null && !userId.toString().equals(owner.toString())) {
            return Result.fail("无权查询该订单");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId.toString());
        result.put("status", String.valueOf(entries.get("status")));
        result.put("message", resolveStatusMessage(entries.get("message")));
        result.put("voucherId", entries.get("voucherId"));
        return Result.ok(result);
    }

    /**
     * MQ 消费入口。写库成功就正常返回，失败往外抛，
     * 由监听器决定是重投还是进死信（见 {@code SeckillVoucherListener}）。
     */
    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        completeVoucherOrder(voucherOrder, "mq");
    }

    /**
     * 重投耗尽、消息已经进了死信：这单确定落不了库，撤销 Redis 侧的预留。
     * <p>
     * 撤销后库存加回去、用户从已购集合里移出（可以重新抢），状态改成失败让前端看到。
     * 死信队列里的消息<b>不重放</b>——那会和这里还回去的预留冲突（重放会再落一次库、
     * 再扣一次 MySQL 库存，而 Redis 库存已经加回去了，就变成可能超卖）。
     * </p>
     *
     * @return true 表示这单的作废已经处理完（回滚成功，或者消息本身缺字段、重试也没用）；
     *         false 表示这次没成功（多半是 Redis 不可用），调用方不能 ACK，要过一会儿重试
     */
    @Override
    public boolean abandonOrder(VoucherOrder order) {
        if (order == null || order.getId() == null || order.getUserId() == null || order.getVoucherId() == null) {
            // 消息里缺 userId/voucherId 就定位不到预留，重试多少次也一样，只能记日志交给人工
            log.error("秒杀订单无法作废：消息缺少必要字段，需要人工检查，order={}", order);
            return true;
        }
        try {
            stringRedisTemplate.execute(
                    ABANDON_SCRIPT,
                    Arrays.asList(
                            SECKILL_ORDER_KEY + order.getVoucherId(),
                            SECKILL_STOCK_KEY + order.getVoucherId(),
                            SECKILL_ORDER_STATUS_KEY + order.getId(),
                            SECKILL_UNDELIVERED_KEY
                    ),
                    order.getUserId().toString(),
                    order.getId().toString(),
                    "下单失败，请重新抢购"
            );
            log.error("秒杀订单重投耗尽已作废，预留已撤销，orderId={}, userId={}, voucherId={}",
                    order.getId(), order.getUserId(), order.getVoucherId());
            return true;
        } catch (Exception e) {
            // 不能吞掉：回滚没成功却 ACK，这单的预留就永远留在 Redis 里了
            log.error("撤销秒杀预留失败，稍后重试，orderId={}", order.getId(), e);
            return false;
        }
    }

    /**
     * 发送订单消息。消息体自带完整订单数据，消费者不依赖 Redis 就能落库。
     */
    private void sendOrderMessage(VoucherOrder order) {
        try {
            rabbitTemplate.convertAndSend(
                    QueueConfig.ORDER_EXCHANGE,
                    QueueConfig.ORDER_ROUTING_KEY,
                    JSONUtil.toJsonStr(order),
                    new CorrelationData(order.getId().toString())
            );
        } catch (Exception e) {
            // 发不出去就把「待投递」标记留着，补发任务每 5 秒重发一次
            log.error("发送秒杀消息失败，稍后由补发任务重发，orderId={}", order.getId(), e);
        }
    }

    /**
     * 补发任务：把还没被 broker 确认收下的订单重新发一遍消息。
     * <p>
     * 它<b>只发消息，不写数据库</b>——写库永远只有消费者一条路。
     * 正常情况下这个集合是空的：发布确认几毫秒就回来了。
     * 发完把分数顶到当前时间，避免同一条被每轮都重发。
     * </p>
     */
    @Scheduled(fixedDelay = 5000)
    public void republishUnconfirmedOrders() {
        try {
            long maxScore = System.currentTimeMillis() - UNDELIVERED_MIN_AGE_MS;
            Set<String> orderIds = stringRedisTemplate.opsForZSet()
                    .rangeByScore(SECKILL_UNDELIVERED_KEY, 0, maxScore, 0, UNDELIVERED_SCAN_BATCH_SIZE);
            if (orderIds == null || orderIds.isEmpty()) {
                return;
            }
            for (String orderIdText : orderIds) {
                Long orderId = parseLong(orderIdText);
                VoucherOrder order = orderId == null ? null : getOrderFromRedis(orderId);
                if (order == null) {
                    // 状态已经没了（订单落库后清掉了，或者数据被删了），没什么可补的
                    stringRedisTemplate.opsForZSet().remove(SECKILL_UNDELIVERED_KEY, orderIdText);
                    continue;
                }
                stringRedisTemplate.opsForZSet()
                        .add(SECKILL_UNDELIVERED_KEY, orderIdText, System.currentTimeMillis());
                log.warn("补发未被确认的秒杀消息，orderId={}", orderId);
                sendOrderMessage(order);
            }
        } catch (Exception e) {
            log.error("补发未确认的秒杀消息失败", e);
        }
    }

    /**
     * 真正写数据库订单的方法，只在 {@link #completeVoucherOrder} 的事务里调用。
     * <p>
     * 这里<b>不吞</b> {@link DuplicateKeyException}：同一条单的重复消息（重投、补发）
     * 可能同时落库，靠 {@code uk_user_voucher} 唯一索引挡住重复。如果在这里 catch 掉，
     * 异常就没能穿过事务边界，事务会被留在「已回滚但仍要提交」的状态，
     * 提交时抛 UnexpectedRollbackException。所以让它抛出去，由外层处理。
     * </p>
     */
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        if (!save(voucherOrder)) {
            throw new IllegalStateException("创建订单失败");
        }

        // 券只有一张表，库存就在 tb_voucher 上，按主键扣。
        // 注意不能用本类继承来的 update()——那个操作的是 tb_voucher_order。
        //
        // 正常流程下扣不到 0：Redis 侧的预占在前，且 MySQL 库存只会比 Redis 多
        //（差额是还没落库的在途订单）。这里仍然带上 stock > 0，作为最后一道护栏：
        // 万一预占和落库的账对不上（人工改过 Redis、Redis 被清空后重新初始化之类），
        // 数据库宁可拒绝这一单，也不能把库存扣成负数。
        boolean deducted = voucherService.update()
                .setSql("stock = stock - 1")
                .eq("id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!deducted) {
            // 抛出去让整个事务回滚，保证「订单没落库」和「库存没扣」是一致的；
            // 之后按普通失败处理：重投，耗尽则进死信并撤销 Redis 预占
            throw new IllegalStateException("数据库库存不足，放弃本次落库");
        }
    }

    /**
     * 把订单写进数据库。<b>失败就往外抛</b>，由监听器决定重投还是进死信——
     * 不再自己吞掉异常，所以「消息 ACK 了但订单没落库」这种情况不存在。
     */
    private void completeVoucherOrder(VoucherOrder voucherOrder, String source) {
        if (getById(voucherOrder.getId()) != null) {
            cleanupOrderState(voucherOrder.getId());
            return;
        }
        if (isAbandoned(voucherOrder.getId())) {
            // 这单已经因为重投耗尽被作废、预留也还回去了，再落库就等于凭空多出一张券。
            // 这里只清「待投递」标记，绝不能删 FAILED 状态——那份状态就是「已作废」这个事实本身，
            // 删了它，后面再来一份重复消息就会被当成「没作废」而落库。
            log.warn("秒杀订单已被作废，不再落库，source={}, orderId={}", source, voucherOrder.getId());
            stringRedisTemplate.opsForZSet().remove(SECKILL_UNDELIVERED_KEY, voucherOrder.getId().toString());
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> createVoucherOrder(voucherOrder));
        } catch (DuplicateKeyException e) {
            // 同一条单的另一份消息已经写进去了（重投或补发造成的重复），算成功。
            // 放在事务之外捕获，事务已经干净回滚，不会出现 UnexpectedRollbackException。
            log.info("秒杀订单已存在，跳过重复写入，source={}, orderId={}", source, voucherOrder.getId());
            cleanupOrderState(voucherOrder.getId());
            return;
        }

        cleanupOrderState(voucherOrder.getId());
        log.info("秒杀订单落库成功，source={}, orderId={}", source, voucherOrder.getId());
    }

    /**
     * 这单是不是已经被作废了（重投耗尽）。
     * 作废时预留已经还回 Redis，所以哪怕还有一条迟到的重复消息，也不能再落库。
     * Redis 读不到就当没作废——比起漏掉一次判断，把订单写丢更糟。
     */
    private boolean isAbandoned(Long orderId) {
        try {
            Object status = stringRedisTemplate.opsForHash().get(SECKILL_ORDER_STATUS_KEY + orderId, "status");
            return "FAILED".equals(status);
        } catch (Exception e) {
            log.warn("查秒杀订单状态失败，按未作废处理，orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 订单已经落库，Redis 里那两份数据就没用了。
     * <p>
     * 先删状态 hash 再删「待投递」标记：万一只做到一半，剩下来的是「标记还在、状态没了」，
     * 补发任务看到没有状态就会把标记清掉，自己能收尾。
     * </p>
     */
    private void cleanupOrderState(Long orderId) {
        try {
            stringRedisTemplate.delete(SECKILL_ORDER_STATUS_KEY + orderId);
            stringRedisTemplate.opsForZSet().remove(SECKILL_UNDELIVERED_KEY, orderId.toString());
        } catch (Exception e) {
            // 订单已经在库里了，这里失败只是多留一点 Redis 数据（状态有 TTL），不能让消息重投
            log.warn("清理秒杀订单的 Redis 状态失败，orderId={}", orderId, e);
        }
    }

    /**
     * 从订单状态 hash 里恢复订单数据，补发消息时用。
     * Lua 预占时会写 userId、voucherId，所以服务重启后也能重建。
     */
    private VoucherOrder getOrderFromRedis(Long orderId) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(SECKILL_ORDER_STATUS_KEY + orderId);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        Long userId = parseLong(entries.get("userId"));
        Long voucherId = parseLong(entries.get("voucherId"));
        if (userId == null || voucherId == null) {
            return null;
        }
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }

    /**
     * 把 Lua 返回码翻译成用户能看懂的提示。
     */
    private String getSeckillFailMessage(int result) {
        if (result == 1) {
            return "库存不足";
        }
        if (result == 2) {
            return "你已抢到资格，请稍后查看订单";
        }
        return "活动库存未初始化";
    }

    /**
     * 把 Redis 里为了避免编码问题保存的状态文案转换成前端展示文案。
     */
    private String resolveStatusMessage(Object message) {
        if ("ORDER_PROCESSING".equals(message)) {
            return "订单处理中";
        }
        return message == null ? null : message.toString();
    }

    /**
     * 安全地把 Redis 中读到的字符串转成 Long。
     */
    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 组装状态查询接口的返回数据。
     */
    private Map<String, Object> buildStatusResult(Long orderId, String status, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId.toString());
        result.put("status", status);
        result.put("message", message);
        return result;
    }
}
