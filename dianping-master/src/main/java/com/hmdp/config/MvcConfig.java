package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.TokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //解析登录令牌，对所有路径生效
        registry.addInterceptor(new TokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
        //要求登录，对所有路径生效；哪些接口对游客开放由 @PublicApi 决定
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**").order(1);
    }
}
