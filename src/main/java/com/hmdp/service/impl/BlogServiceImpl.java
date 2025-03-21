package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
        // 1. get the current user
        Long userId = UserHolder.getUser().getId();
        // 2. check the user like or not
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean IsMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(IsMember));
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. get the current user
        Long userId = UserHolder.getUser().getId();
        // 2. check the user like or not
        String key = BLOG_LIKED_KEY + id;
        Boolean IsMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(IsMember)) {
            // 3. could like if not
            // 3.1 update database
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 put user into redis
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 4. cancel like if it has liked
            // 4.1 update database
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 remove user from redis
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }
}
