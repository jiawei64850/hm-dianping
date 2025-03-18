package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static final long BEGIN_TIMESTAMP = 1735689600L;
    public static final int COUNT_BITS = 32;
    public long nextId(String keyPrefix) {
        // 1. generate the timestamp
        LocalDateTime now = LocalDateTime.now();
        long curr = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = curr - BEGIN_TIMESTAMP;
        // 2. generate the serial number
        // 2.1 get the current date (add to serial number)
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 increment
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3. splice and return it
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2025, 1, 1, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second: " + second);
    }
}
