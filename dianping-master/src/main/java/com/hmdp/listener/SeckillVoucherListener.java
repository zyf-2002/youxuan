package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.QueueConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <p>
 * 秒杀订单的 MQ 消费者：<b>落库成功才 ACK</b>。
 * </p>
 * <ul>
 *   <li>写库失败（数据库连不上、超时、锁等待）→ 转发到延迟队列，{@code x-retry-count} 加 1，
 *       等 15 秒回到主队列重投；</li>
 *   <li>重投到 {@link QueueConfig#MAX_RETRY_COUNT} 次还是失败 → 消息进死信队列，
 *       同时撤销 Redis 侧的预留（用户看到「下单失败」，可以重新抢）；</li>
 *   <li>消息体坏了 → 解析异常直接抛出去。手动 ACK 模式下容器不会替你 nack，
 *       这条消息就一直挂在 unacked，应用重启时才被重投、再失败一次；</li>
 *   <li>转发本身失败（broker 不正常）→ 不 ACK，把原消息退回 broker 让它重投。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    private final IVoucherOrderService voucherOrderService;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = QueueConfig.ORDER_QUEUE)
    public void receiveOrder(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());

        VoucherOrder order = JSONUtil.toBean(body, VoucherOrder.class);

        try {
            voucherOrderService.handleVoucherOrder(order);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // handleVoucherOrder 只在没落库时才抛异常，所以走到这里就是这一单还没写进数据库
            int retryCount = getRetryCount(message);
            try {
                if (retryCount < QueueConfig.MAX_RETRY_COUNT) {
                    log.warn("秒杀订单落库失败，准备第 {} 次重投，orderId={}", retryCount + 1, order.getId(), e);
                    sendToRetry(body, retryCount + 1);
                } else {
                    // 重投次数用完：放死信 + 撤销 Redis 预留。两件事都成了才 ACK。
                    if (retryCount == QueueConfig.MAX_RETRY_COUNT) {
                        // 顺序不能反：先把消息放进死信，再撤销预留。
                        // 万一这中间进程挂了，留下的是「预留还在」这种保守状态，不会超卖。
                        log.error("秒杀订单重投 {} 次仍未落库，进死信，orderId={}", retryCount, order.getId(), e);
                        sendToDead(body);
                    }
                    if (!voucherOrderService.abandonOrder(order)) {
                        // 预留没撤销成功（多半是 Redis 不可用）：不能 ACK，
                        // 过 15 秒回来重试撤销，直到成功。计数继续加，死信不会被重复塞。
                        log.error("秒杀订单作废未成功，稍后重试撤销，orderId={}", order.getId());
                        sendToRetry(body, retryCount + 1);
                    }
                }
            } catch (Exception forwardException) {
                // 转发不出去就不确认原消息，退给 broker 重投
                log.error("转发秒杀消息失败，原消息不确认，交回 broker，orderId={}", order.getId(), forwardException);
                channel.basicNack(deliveryTag, false, true);
                return;
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 把消息放进延迟队列，等 TTL 到点由 broker 送回主队列。
     */
    private void sendToRetry(String body, int retryCount) {
        rabbitTemplate.convertAndSend(
                QueueConfig.ORDER_EXCHANGE,
                QueueConfig.RETRY_ROUTING_KEY,
                body,
                message -> {
                    message.getMessageProperties().setHeader(QueueConfig.RETRY_COUNT_HEADER, retryCount);
                    return message;
                });
    }

    /**
     * 把消息放进死信队列。没有消费者，堆着等人看。
     */
    private void sendToDead(String body) {
        rabbitTemplate.convertAndSend(QueueConfig.ORDER_EXCHANGE, QueueConfig.DEAD_ROUTING_KEY, body);
    }

    /**
     * 读出重投次数。第一次投递没有这个头，按 0 算。头坏了也按 0 算。
     */
    private int getRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(QueueConfig.RETRY_COUNT_HEADER);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
