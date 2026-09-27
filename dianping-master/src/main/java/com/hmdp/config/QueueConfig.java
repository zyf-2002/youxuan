package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>
 * 秒杀订单的 MQ 拓扑：一个 exchange、三个队列。
 * </p>
 * <ul>
 *   <li>{@code seckill.order} —— 主队列。消费成功才 ACK，失败的消息由消费者转发到重投队列。</li>
 *   <li>{@code seckill.order.retry} —— 延迟队列。带 TTL，到点由 broker 送回主队列，
 *       这样重投不是「立刻再来一遍」，不会在数据库挂掉时把它打死。</li>
 *   <li>{@code seckill.order.dead} —— 死信队列。重投超过上限、或者消息体根本解析不了才进来。
 *       没有消费者，堆着等人看（日志里同时有 ERROR），不自动重放。</li>
 * </ul>
 * <p>
 * <b>重试只由 MQ 负责</b>：应用侧不再有第二个写数据库的路径。
 * Redis 那边只留一个「待投递」集合，负责「抢到了但消息还没交给 broker」这一段，
 * 收到发布确认就删掉（见 {@code VoucherOrderServiceImpl#republishUnconfirmedOrders}）。
 * </p>
 */
@Configuration
public class QueueConfig {

    public static final String ORDER_EXCHANGE = "seckill.order.exchange";

    public static final String ORDER_QUEUE = "seckill.order.queue";
    public static final String RETRY_QUEUE = "seckill.order.retry.queue";
    public static final String DEAD_QUEUE = "seckill.order.dead.queue";

    public static final String ORDER_ROUTING_KEY = "seckill.order";
    public static final String RETRY_ROUTING_KEY = "seckill.order.retry";
    public static final String DEAD_ROUTING_KEY = "seckill.order.dead";

    /** 重投前等多久，也就是重投队列的 TTL */
    public static final int RETRY_DELAY_MS = 15000;
    /** 重投多少次之后进死信：15 秒 × 20 ≈ 5 分钟 */
    public static final int MAX_RETRY_COUNT = 20;
    /** 消息头里记重投次数的字段，每转发一次加 1 */
    public static final String RETRY_COUNT_HEADER = "x-retry-count";

    @Bean("seckillOrderExchange")
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean("seckillOrderQueue")
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE).build();
    }

    @Bean("seckillRetryQueue")
    public Queue seckillRetryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(RETRY_DELAY_MS)
                .deadLetterExchange(ORDER_EXCHANGE)
                .deadLetterRoutingKey(ORDER_ROUTING_KEY)
                .build();
    }

    @Bean("seckillDeadQueue")
    public Queue seckillDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding orderQueueBinding(
            @Qualifier("seckillOrderQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding retryQueueBinding(
            @Qualifier("seckillRetryQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding deadQueueBinding(
            @Qualifier("seckillDeadQueue") Queue queue,
            @Qualifier("seckillOrderExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(DEAD_ROUTING_KEY);
    }
}
