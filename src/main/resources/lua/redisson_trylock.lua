local key = KEY[1] -- 锁的key
local threadId = ARGV[1]   -- 线程唯一标识
local releaseTime = ARGV[2]    -- 锁的自动释放时间

-- 判断是否存在
if(redis.call('exists', key) == 0) then
    -- 不存在，获取锁
    redis.call('hset', key, threadId, '1')
    -- 设置有效期
    redis.call('expire', key, releaseTime)

    return 1;
end

-- 锁已存在，判断threadId是否是自己
if(redis.call('hexists', key, threadId) == 1) then
    -- 获取锁，重入次数 + 1
    redis.call('hincrby', key, threadId, '1')
    -- 设置有效期
    redis.call('expire', key, releaseTime)

    return 1
end
-- 获取锁失败
return 0