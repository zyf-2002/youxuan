package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            setNullValue(key);
            return null;
        }

        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            if (json != null) {
                return null;
            }
            return queryWithMutexAndLogicalExpire(keyPrefix, id, type, dbFallback, time, unit);
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime == null) {
            return JSONUtil.toBean(json, type);
        }
        R data = convertRedisData(redisData, type);
        if (expireTime.isAfter(LocalDateTime.now())) {
            return data;
        }

        String lockKey = RedisConstants.LOCK_SHOP_KEY + key;
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            RLock lock = redissonClient.getLock(lockKey);
            try {
                if (tryLock(lock)) {
                    R freshData = dbFallback.apply(id);
                    setLogicalExpireValue(key, freshData, time, unit);
                }
            } catch (Exception e) {
                log.error("Failed to rebuild cache, key={}", key, e);
            } finally {
                unLock(lock);
            }
        });

        return data;
    }

    //当redis key为null并且不是空字符串的时候需要调用这个函数重构缓存
    private <R, ID> R queryWithMutexAndLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + key;
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLock = tryLock(lock);
        if (!isLock) {
            sleep(50);
            return queryWithLogicalExpire(keyPrefix, id, type, dbFallback, time, unit);
        }

        try {                //这里二次判断是因为可能之前有线程重建好了
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return convertCacheValue(json, type);
            }
            if (json != null) {
                return null;
            }

            R data = dbFallback.apply(id);
            setLogicalExpireValue(key, data, time, unit);
            return data;
        } finally {
            unLock(lock);
        }
    }

    private <R> R convertCacheValue(String json, Class<R> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData.getExpireTime() == null) {
            return JSONUtil.toBean(json, type);
        }
        return convertRedisData(redisData, type);
    }

    private <R> R convertRedisData(RedisData redisData, Class<R> type) {
        Object data = redisData.getData();
        if (data == null) {
            return null;
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
    }

    private void setLogicalExpireValue(String key, Object value, Long time, TimeUnit unit) {
        if (value == null) {
            setNullValue(key);
            return;
        }
        this.setWithLogicalExpire(key, value, time, unit);
    }

    private void setNullValue(String key) {
        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(0, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unLock(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
