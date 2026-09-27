package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL_MINUTES = 2L;
    /** 登录令牌 key，值是该用户的 UserDTO JSON */
    public static final String LOGIN_USER_KEY = "login:token:";
    /** 登录令牌有效期，每次带令牌的请求都会续满，所以活跃用户不会掉线 */
    public static final Long LOGIN_USER_TTL_MINUTES = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String SHOP_BLOOM_KEY = "shop:bloom";
    public static final Long SHOP_BLOOM_EXPECTED_INSERTIONS = 100000L;
    public static final Double SHOP_BLOOM_FALSE_PROBABILITY = 0.01D;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_ORDER_STATUS_KEY = "seckill:order:status:";
    /**
     * 还没被 broker 确认收下的订单号。发布确认一到就删掉，正常永远是空的；
     * 删不掉（broker 挂了、进程崩了）就由补发任务重发消息。
     */
    public static final String SECKILL_UNDELIVERED_KEY = "seckill:order:undelivered";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_TYPE_KEY = "shop_type:";
    public static final Long SHOP_TYPE_LONG=10L;
}
