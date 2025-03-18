package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 18/03/2025
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        // pass through the cache
        // Shop shop = queryWithPassThrough(id);
        // get the mutual exclusion
        // Shop shop = queryWithMutex(id);
        // solve out pass-through problem with method of logical expire
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }


    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. query the cache of shop from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isBlank(shopJson)) {
            // 3. return null if not exists (not hit)
            return null;
        }
        // 4. anti-serialize json into object if hit (exists)
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. check if it is expired or not
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 return info of shop if not expire
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // release lock
                    unlock(lockKey);
                }
            });
        }
        // 6.4 return expired info of shop whatever successful or not
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. query the cache of shop from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. return the information if exists
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }
        // 4. implement the cache rebuild if no cache
        // 4.1 get the mutex
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 check works or not
            if (!isLock) {
                // 4.3 sleep for a while and retry if not
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 check again the cache exists or not (DoubleCheck)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 4.4.1 check the cache exists or not
            if (StrUtil.isNotBlank(shopJson)) {
                // 4.4.2 return the information if exists
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 4.5 query the database based on shop id if getting mutex
            shop = getById(id);
            // 4.6 simulate the case of delay of redis rebuild
            // Thread.sleep(200);
            // 5. check the shop in database exists or not
            if (shop == null) {
                // 5.1 put null value into redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. return error msg and set status if not
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. release the mutex
            unlock(lockKey);
        }
        // 8. put information into redis and return it if exists (in database)
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. query the cache of shop from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. return the information if exists
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }
        // 4. query the database based on shop id if not
        Shop shop = getById(id);
        // 5. check the shop in database exists or not
        if (shop == null) {
            // 5.1 put null value into redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. return error msg and set status if not
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. put information into redis and return it if exists (in database)
        return shop;
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            Result.fail("店铺id不能为空");
        }
        // 1. update database
        updateById(shop);
        // 2. delete cache
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. query the info of shop
        Shop shop = getById(id);
        // 1.1 simulate the case of delay of redis rebuild
        Thread.sleep(200);
        // 2. wrap into the logic expired time
        RedisData rd = new RedisData();
        rd.setData(shop);
        rd.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. store into redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(rd));
    }
}

