-- 重投耗尽，这单确定落不了库：撤销 Redis 侧的预留，并把状态改成失败。
-- 只有真把这个用户从已购集合里删掉了才加库存——同一条单的重复消息可能都走到这里，
-- 不加这个判断会把库存加多，Redis 库存就会高于数据库，变成可能超卖。
-- KEYS[1]: 已购用户集合
-- KEYS[2]: 库存 key
-- KEYS[3]: 订单状态 hash
-- KEYS[4]: 待投递 ZSet
-- ARGV[1]: user id
-- ARGV[2]: order id
-- ARGV[3]: 失败提示

local removed = redis.call('srem', KEYS[1], ARGV[1])
if removed == 1 then
    redis.call('incr', KEYS[2])
    -- 状态改成失败，同样不设 TTL：它要一直留着，挡住迟到的重复消息再落库
    redis.call('hset', KEYS[3], 'status', 'FAILED', 'message', ARGV[3])
end

-- 这单已经作废，不能再被补发任务重发出去
redis.call('zrem', KEYS[4], ARGV[2])

return removed
