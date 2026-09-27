package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;


@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addVoucher(Voucher voucher) {
        // 券只有一张表：库存与起止时间都在 tb_voucher 里，一次 save 就够
        save(voucher);
        // 库存写进 Redis，下单时直接扣这里
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(),
                voucher.getStock().toString());
    }

    @Override
    public int initMissingStockInRedis() {
        List<Voucher> vouchers = list();
        int filled = 0;
        for (Voucher voucher : vouchers) {
            if (voucher.getId() == null || voucher.getStock() == null) {
                continue;
            }
            // 只在 key 不存在时写。Redis 里的库存是「已经扣过」的实时值，
            // 数据库要等订单落库才扣、永远滞后；无脑覆盖会把处理中的订单重复放出去（超卖）。
            Boolean absent = stringRedisTemplate.opsForValue()
                    .setIfAbsent(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
            if (Boolean.TRUE.equals(absent)) {
                filled++;
            }
        }
        log.info("补写 Redis 库存完成：新增 {} 个 key，共 {} 张券", filled, vouchers.size());
        return filled;
    }
}
