package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expireTime, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        // create logic expire object
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        // write cache into redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long expireTime,
            TimeUnit timeUnit
            ) {
        String key = keyPrefix + id;
        // 1. query the cache of r from redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isNotBlank(json)) {
            // 3. return the information if exists
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }
        // 4. query the database based on r id if not
        R r = dbFallback.apply(id);
        // 5. check the shop in database exists or not
        if (r == null) {
            // 5.1 put null value into redis
            stringRedisTemplate.opsForValue().set(key, "", expireTime, timeUnit);
            return null;
        }
        // 6. return error msg and set status if not
        this.set(key, r, expireTime, timeUnit);
        // 7. put information into redis and return it if exists (in database)
        return r;
    }


    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit ) {
        String key = keyPrefix + id;
        // 1. query the cache of shop from redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isBlank(json)) {
            // 3. return null if not exists (not hit)
            return null;
        }
        // 4. anti-serialize json into object if hit (exists)
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. check if it is expired or not
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 return info of shop if not expire
            return r;
        }
        // 5.2 require cache rebuild if expire
        // 6. rebuild cache
        // 6.1 get the mutex
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 check if getting mutex successfully or not
        if (isLock) {
            // 6.3 activate independent thread for cache rebuild if successful
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // rebuild cache
                try {
                    // query the database
                    R r1 = dbFallback.apply(id);
                    // write into redis
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // release lock
                    unlock(lockKey);
                }
            });
        }
        // 6.4 return expired info of shop whatever successful or not
        return r;
    }

    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
