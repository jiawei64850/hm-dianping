package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public static final String KEY_PREFIX = "lock:";
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    @Override
    public boolean tryLock(long timeOutSec) {
        // 1. get the id of thread and UUID as value (thread identification)
        String value = ID_PREFIX + Thread.currentThread().getId();
        // 2. get the mutex
        String key = KEY_PREFIX + name;
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeOutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        // get the identification of thread
        String value = ID_PREFIX + Thread.currentThread().getId();
        // get the identification of mutex
        String key = KEY_PREFIX + name;
        String id = stringRedisTemplate.opsForValue().get(key);
        // check the same or not
        if (value.equals(id)) {
            // release the mutex
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
