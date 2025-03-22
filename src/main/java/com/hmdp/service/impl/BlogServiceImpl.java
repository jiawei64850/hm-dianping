package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 21/03/2025
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((blog -> {
            this.queryBlogUser(blog);
            this.isLikedBlog(blog);
        }));
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. query the blog
        Blog blog = getById(id);
        // 2. query the user based on blog
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        // 3. query the blog has liked or not
        isLikedBlog(blog);
        return Result.ok(blog);
    }

    private void isLikedBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        // without login, no need to query whether like or not
        if (user == null) {
            return;
        }
        // 1. get the current user
        Long userId = UserHolder.getUser().getId();
        // 2. check the user like or not
        String key = BLOG_LIKED_KEY + blog.getId();
        // Boolean IsMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        // blog.setIsLike(BooleanUtil.isTrue(IsMember));
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {

        // 1. get the current user
        Long userId = UserHolder.getUser().getId();
        // 2. check the user like or not
        String key = BLOG_LIKED_KEY + id;
        // Boolean IsMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) { // BooleanUtil.isFalse(IsMember)
            // 3. could like if not
            // 3.1 update database
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 put user into redis
            if (isSuccess) {
                // stringRedisTemplate.opsForSet().add(key, userId.toString());
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());

            }
        } else {
            // 4. cancel like if it has liked
            // 4.1 update database
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 remove user from redis
            if (isSuccess) {
                // stringRedisTemplate.opsForSet().remove(key, userId.toString());
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {

        // 1. query the user based on top 5 likes number
        String key = BLOG_LIKED_KEY + id;
        // 2. parse the user id based on likes record
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null) {
            return Result.ok(Collections.emptyList());
        }
        // 3. query the user based on user id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4. return
        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // get the current user
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // save note
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        // query the all fans of the note
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId())
                .list();
        // send the id of node to all fans
        for (Follow follow : follows) {
            // get the id of fan
            Long userId = follow.getUserId();
            // send to fan
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet()
                    .add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // return id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. get the current user
        Long userId = UserHolder.getUser().getId();
        // 2. query the feed inbox ZREVERANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {return Result.ok();}
        // 3. parse the data: blogId, minTime (timestamp), offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 3.1 get the id
            String idStr = typedTuple.getValue();
            ids.add(Long.valueOf(idStr));
            // 3.2 get the score (timestamp)
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 4. query the blog based on id
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + idStr + ")")
                .list();

        for (Blog blog : blogs) {
            // 4.1 query the user based on blog
            if (blog == null) {
                return Result.fail("笔记不存在！");
            }
            queryBlogUser(blog);
            // 4.2 query the blog has liked or not
            isLikedBlog(blog);
        }
        // 5. encapsulate the result
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
