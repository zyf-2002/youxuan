package com.hmdp.interceptor;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

/**
 * <p>
 * 解析请求头里的登录令牌，把用户放进 {@link UserHolder}，并把令牌有效期续满。
 * </p>
 * <p>
 * 令牌是一串随机字符，本身不携带任何信息，用户身份只存在于 Redis 的
 * {@code login:token:<令牌>} 里。好处是退出登录、封号只要删掉这个 key 就立即生效，
 * 不需要黑名单，也不需要等服务端签名过期。
 * </p>
 * <p>
 * 没带令牌、令牌无效、令牌已过期，这里都静默放行，是否拦截交给 {@link LoginInterceptor}。
 * </p>
 */
public class TokenInterceptor implements HandlerInterceptor {

    private static final String TOKEN_HEADER = "authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final StringRedisTemplate stringRedisTemplate;

    public TokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveToken(request.getHeader(TOKEN_HEADER));
        if (StrUtil.isBlank(token)) {
            return true;
        }

        String key = RedisConstants.LOGIN_USER_KEY + token;
        // GETEX：取值的同时把有效期续满，一次往返完成，也避免取值和续期之间令牌刚好过期
        String json = stringRedisTemplate.opsForValue()
                .getAndExpire(key, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL_MINUTES));
        if (StrUtil.isBlank(json)) {
            return true;
        }

        UserHolder.saveUser(JSONUtil.toBean(json, UserDTO.class));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserHolder.removeUser();
    }

    /**
     * 从请求头取出令牌。兼容标准的 {@code Authorization: Bearer <令牌>} 写法，
     * 也接受直接放令牌。退出登录要对同一个 key 做删除，所以这里必须是 public。
     */
    public static String resolveToken(String header) {
        if (StrUtil.isBlank(header)) {
            return null;
        }
        String token = header.trim();
        if (StrUtil.startWithIgnoreCase(token, BEARER_PREFIX)) {
            return token.substring(BEARER_PREFIX.length()).trim();
        }
        return token;
    }
}
