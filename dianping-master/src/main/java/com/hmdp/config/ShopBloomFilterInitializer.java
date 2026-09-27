package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.ShopBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ShopBloomFilterInitializer implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private ShopBloomFilter shopBloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Long> shopIds = shopService.lambdaQuery()
                    .select(Shop::getId)
                    .list()
                    .stream()
                    .map(Shop::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            shopBloomFilter.init(shopIds);
        } catch (Exception e) {
            log.warn("Initialize shop bloom filter failed, skip bloom filter for shop queries", e);
        }
    }
}
