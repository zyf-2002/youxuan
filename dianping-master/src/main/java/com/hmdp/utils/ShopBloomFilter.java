package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

@Component
@Slf4j
public class ShopBloomFilter {

    @Resource
    private RedissonClient redissonClient;

    private volatile boolean initialized;

    public void init(List<Long> shopIds) {
        RBloomFilter<Long> filter = getFilter();
        filter.tryInit(
                RedisConstants.SHOP_BLOOM_EXPECTED_INSERTIONS,
                RedisConstants.SHOP_BLOOM_FALSE_PROBABILITY
        );
        if (shopIds != null) {
            for (Long shopId : shopIds) {
                if (isValidShopId(shopId)) {
                    filter.add(shopId);
                }
            }
        }
        initialized = true;
        log.info("Shop bloom filter initialized, shopIdCount={}", shopIds == null ? 0 : shopIds.size());
    }

    public boolean mightContain(Long shopId) {
        if (!isValidShopId(shopId)) {
            return false;
        }
        if (!initialized) {    //如果没有初始化相当于没有布隆过滤器，直接放行
            return true;
        }
        try {
            RBloomFilter<Long> filter = getFilter();
            if (!filter.isExists()) {
                initialized = false;
                return true;
            }
            return filter.contains(shopId);
        } catch (Exception e) {
            log.warn("Shop bloom filter check failed, shopId={}", shopId, e);
            return true;
        }
    }

    public void add(Long shopId) {
        if (!isValidShopId(shopId) || !initialized) {
            return;
        }
        try {
            RBloomFilter<Long> filter = getFilter();
            if (filter.isExists()) {
                filter.add(shopId);
            } else {
                initialized = false;
            }
        } catch (Exception e) {
            log.warn("Shop bloom filter add failed, shopId={}", shopId, e);
        }
    }

    private RBloomFilter<Long> getFilter() {
        return redissonClient.getBloomFilter(RedisConstants.SHOP_BLOOM_KEY);
    }

    private boolean isValidShopId(Long shopId) {
        return shopId != null && shopId > 0;
    }
}
