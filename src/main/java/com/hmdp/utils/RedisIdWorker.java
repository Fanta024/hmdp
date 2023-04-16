package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    public static final Long BEGIN_TIMESTAMP = 1672531200L;
    public static final int COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nexId(String keyPrefix) {
        //生成时间戳
        LocalDateTime nowTime = LocalDateTime.now();
        long nowStamp = nowTime.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowStamp - BEGIN_TIMESTAMP;
        //生成序列号
        String date = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接
        return timeStamp << COUNT_BITS | count;

    }
}
