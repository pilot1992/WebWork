local key = KEY[1]; -- 锁的key
local threadId = ARGV[1];   -- 线程唯一标识
local releaseTime = ARGV[2];    -- 锁的自动释放时间

-- 判断锁是否还是被自己持有
if(redis.call('hexists', key, threadId) == 0) then
    return nil; -- 不是自己，直接返回
end

-- 是自己的锁，重入次数 - 1
local count = redis.call('hincrby', key, threadId, -1);

-- 判断重入次数是否是0
if(count > 0) then
    -- 不能释放锁，重置有效期后返回
    redis.call('expire', key, releaseTime);
    return nil;
else
    -- 等于0释放锁
    redis.call('del', key);
    return nil;
end
