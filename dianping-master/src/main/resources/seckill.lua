-- 秒杀预占：一次原子操作完成「判资格 + 扣库存 + 记状态 + 记待投递」。
-- 这里只碰 Redis，没有库存 key 就直接拒掉（能不能买只看这个 key）。
-- KEYS[1]: 库存 key
-- KEYS[2]: 已购用户集合
-- KEYS[3]: 订单状态 hash
-- KEYS[4]: 待投递 ZSet
-- ARGV[1]: user id
-- ARGV[2]: voucher id
-- ARGV[3]: order id
-- ARGV[4]: 抢购时间戳（毫秒）

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local statusKey = KEYS[3]
local undeliveredKey = KEYS[4]

local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]
local createTime = ARGV[4]

local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    return -1
end

if stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', statusKey,
        'status', 'PROCESSING',
        'message', 'ORDER_PROCESSING',
        'userId', userId,
        'voucherId', voucherId,
        'createTime', createTime)
-- 这份状态故意不设 TTL：它承载着「这单还没落库」和「补发时要用的用户/券信息」这些事实，
-- 靠过期时间来记事实，迟早会把还在用的数据悄悄弄丢。订单落库后 cleanupOrderState 会主动删它。
redis.call('zadd', undeliveredKey, createTime, orderId)

return 0
