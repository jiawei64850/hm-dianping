package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void addInterceptors(InterceptorRegistry registry) {
        // login interceptor
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/voucher/**",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/user/login",
                        "/user/code",
                        "/blog/hot"
                )
                .order(1);
        // refresh interceptor
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
    }
}
