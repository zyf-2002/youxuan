package com.hmdp.interceptor;

import com.hmdp.annotation.PublicApi;
import com.hmdp.utils.UserHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <p>
 * 要求登录：默认所有接口都要登录，只有标了 {@link PublicApi} 的方法放行。
 * </p>
 * <p>
 * 因为判断的是方法而不是 URL，所以既不会出现「/shop/{id} 公开、POST /shop 也公开」
 * 这种被前缀一起带走的情况，也不存在「/user/{id} 公开但 /user/me 要登录」这种
 * 光看路径分不开的情况。
 * </p>
 * <p>
 * 拿不到 {@link HandlerMethod}（例如静态资源）时按「要登录」处理：宁可拦错，也不放过。
 * </p>
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 容器内部的错误转发（转发到 /error）不拦，否则游客遇到异常看到的是 401 而不是错误页
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        if (handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(PublicApi.class)) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
