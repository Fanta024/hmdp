package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock{
    private final StringRedisTemplate stringRedisTemplate;
    private final String name;
    public static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.fastUUID().toString(true)+'-';//toString(true)可以去掉生成uuid的横线
    private static final DefaultRedisScript<Object> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean tryLock(Long timeSec) {
        long threadId = Thread.currentThread().getId();
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, ID_PREFIX+threadId, timeSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLock);

    }
    @Override
    public void unLock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),ID_PREFIX+Thread.currentThread().getId());
    }

//    @Override
//    public void unLock() {
//        String key=KEY_PREFIX+name;
//        String threadId=ID_PREFIX+Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(key);
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(key);
//        }
//    }


}
