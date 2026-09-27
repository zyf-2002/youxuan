package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 抢购。返回订单号只代表抢到资格，落库结果要另查 {@link #querySeckillOrderStatus(Long)}
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 查秒杀订单的处理结果，前端抢到资格后轮询它
     */
    Result querySeckillOrderStatus(Long orderId);

    /**
     * MQ 消费入口。落库成功正常返回；失败往外抛，由监听器决定重投还是进死信。
     */
    void handleVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 重投耗尽、消息已进死信：撤销 Redis 侧的预留（库存加回、用户移出已购集合），
     * 并把状态改成失败让前端看到
     *
     * @return true 表示作废已处理完；false 表示这次没成功（多是 Redis 不可用），
     *         调用方不能 ACK，要过一会儿重试
     */
    boolean abandonOrder(VoucherOrder voucherOrder);
}
