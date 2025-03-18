package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        String key = CACHE_SHOP_KEY + id;
        // 1. query the cache of shop from redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. check the cache exists or not
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. return the information if exists
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4. query the database based on shop id if not
        Shop shop = getById(id);
        // 5. check the shop in database exists or not
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 6. return error msg and set status if not
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        // 7. put information into redis and return it if exists (in database)
        return Result.ok(shop);
    }
}
