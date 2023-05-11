local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId= ARGV[3]
-- 库存
local stockKey = "seckill:stock:" .. voucherId

-- 订单
local orderKey = "seckill:order:" .. voucherId

if (tonumber(redis.call('get',stockKey)) <=0) then
    return 1
end
-- 判断用户是否下单
if (redis.call('sismember', orderKey,userId) == 1) then
    -- 已存在 重复下单
    return 2
end

-- 扣减库存（redis预扣减）
redis.call('incrby',stockKey,-1)
-- 下单
redis.call('sadd',orderKey,userId)
-- 存入消息队列  xadd stream.orders * k1 v1 k2 v2
redis.call("xadd","stream.orders","*","userId",userId,"voucherId",voucherId,"id",orderId)
return 0