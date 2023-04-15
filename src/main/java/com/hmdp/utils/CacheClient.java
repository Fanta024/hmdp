package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public static final ExecutorService executor = Executors.newFixedThreadPool(5);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param lockKey
     * @return true 没有锁  false 有锁
     */
    public boolean tryLock(String lockKey) {
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lock);
    }

    public void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);

    }
    public void set2Redis(String key,Object data,Long time,TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), time, timeUnit);

    }
    public void setWithLogicalExpire(String key, Object data, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(data);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //缓存穿透
    public <R,ID> R queryWithCacheThrow(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(data)) {
            return JSONUtil.toBean(data, type);
        }
        if (Objects.equals(data, "") ) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            //存入空值到redis 解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        this.set2Redis(key, r, time, timeUnit);
        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback) {
        String key = keyPrefix + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(data)) {
            return JSONUtil.toBean(data, type);
        }

        if (Objects.equals(data, "")) {
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            System.out.println(isLock);
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback);
            }

            r = dbFallback.apply(id);
            if (r == null) {
                //存入空值到redis 解决缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //解锁
            unlock(lockKey);
        }

        return r;
    }
    public <R,ID> R queryWithLogicDelete(String keyPrefix, ID id,Class<R> type,Function<ID, R> dbFallback,Long time,TimeUnit timeUnit){
       String key=keyPrefix+id;
       String data = stringRedisTemplate.opsForValue().get(key);
       if(StrUtil.isBlank(data)){
           return null;  //不是热点key
       }
        RedisData redisData = JSONUtil.toBean(data, RedisData.class);
        JSONObject jsonObj = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObj, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return r;
        }
        //过期 获取锁 更新数据
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //拿到了锁   开启新线程更新
            executor.submit(()->{
               //判断此时是否过期
               if(expireTime.isAfter(LocalDateTime.now())){
                   //未过期  数据已更新 结束
                   return;
               }
                //过期 更新数据
                //从数据库获取数据 更新到redis
                try {
                    R newR = dbFallback.apply(id);
                    setWithLogicalExpire(key,newR,time,timeUnit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        //已有线程在更新 先返回过期数据
        return r;
    }

}


