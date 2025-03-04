local voucherId = ARGV[1]   -- 优惠券id
local userId = ARGV[2]  -- 用户id
local stockKey = 'seckill:stock:' .. voucherId  -- 库存key
local orderKey = 'seckill:order' .. voucherId   -- 订单key

-- 判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 存在，重复下单
    return 2
end

-- 扣库存
redis.call('incrby', stockKey, -1)

-- 下单
redis.call('sadd', orderKey, userId)

return 0