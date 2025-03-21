-- 1. parameter list
-- 1.1 order id
local voucherId = ARGV[1]
-- 1.2 user id
local userId = ARGV[2]
-- 1.3 order id
local orderId = ARGV[3]

-- 2. data key
-- 2.1 stock key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 voucher key
local orderKey = 'seckill:order:' .. voucherId

-- 3. business operation
-- 3.1 check if stock is enough or not
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2 return 1 when stock is not enough
    return 1
end
-- 3.3 check if user have order or not
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 3.4 represent repeated order if exist
    return 2
end
-- 3.5 subtract the stock
redis.call('incrby', stockKey, -1)
-- 3.6 store the user into redis -- take order
redis.call('sadd', orderKey, userId)
-- 3.7 send msg into message list
redis.call('xadd', 'stream.orders', '*', 'voucherId', voucherId, 'userId', userId, 'id', orderId)
return 0