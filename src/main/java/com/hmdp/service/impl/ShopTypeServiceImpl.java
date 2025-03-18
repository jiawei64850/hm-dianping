package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 18/03/2025
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 1. get type from redis
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_TYPE_KEY);
        // 2. check cache exists or not in redis
        if (StrUtil.isNotBlank(typeJson)) {
            // 3. return the type info if exists
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 4. query type in database if not exists in redis
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 4. return error msg and set status if not exists in database
        if (shopTypeList == null || shopTypeList.isEmpty()) {
            return Result.fail("店铺不存在！");
        }
        // 5. put it into redis and return type if exists in database
        stringRedisTemplate.opsForValue().set(CACHE_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList));
        // 6. return type list
        return Result.ok(shopTypeList);
    }
}
