package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.FOLLOWER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 22/03/2025
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // get current user
        Long userId = UserHolder.getUser().getId();
        // check follow or cancel
        if (isFollow) {
            // follow, update data into database and redis
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                String key = FOLLOWER_KEY + userId;
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // cancel, delete data from database and redis
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
//            remove(new QueryWrapper<Follow>()
//                    .eq("user_id", userId)
//                    .eq("follow_user_id", followUserId));
            if (isSuccess) {
                String key = FOLLOWER_KEY + userId;
                stringRedisTemplate.delete(key);
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // get current user
        Long userId = UserHolder.getUser().getId();
        // query follow or not
        Integer count = query().eq("follow_user_id", followUserId)
                .eq("user_id", userId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        // get the current user
        Long userId = UserHolder.getUser().getId();
        String k1 = FOLLOWER_KEY + userId;
        String k2 = FOLLOWER_KEY + followUserId;
        // get the intersect set
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(k1, k2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // parse the set
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        Stream<UserDTO> users = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(users);
    }
}
