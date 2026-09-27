package com.hmdp.controller;


import com.hmdp.annotation.PublicApi;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增优惠券（券只有一种，创建时一并写入库存和起止时间）
     *
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.addVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @PublicApi
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }

    /**
     * 补写 Redis 库存（运维接口）。
     * <p>
     * Redis 被清空或丢失后，秒杀会一律报「活动库存未初始化」，而库存本来就在数据库里，
     * 用它把缺失的 key 铺回去即可。只补不存在的 key，不会覆盖正在扣减的计数。
     * </p>
     *
     * @return 补写的 key 数量
     */
    @PostMapping("/stock/init")
    public Result initStock() {
        return Result.ok(voucherService.initMissingStockInRedis());
    }
}
