package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addVoucher(Voucher voucher);

    /**
     * 把数据库里的库存补写进 Redis，只补缺失的 key（不覆盖已有计数）。
     * 用于 Redis 被清空/丢失之后恢复秒杀能力，否则所有券都会报「活动库存未初始化」。
     *
     * @return 实际补写的数量
     */
    int initMissingStockInRedis();
}
