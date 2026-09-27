package com.hmdp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>
 * 标在 Controller 方法上，表示这个接口游客也能访问，不需要登录。
 * </p>
 * <p>
 * 鉴权默认是「全部接口都要登录」，只有标了这个注解的方法才放行。判断依据是方法和
 * 注解，不是 URL，所以加接口时不会因为忘记维护一张路径清单而把新接口误开放出去，
 * 也不存在「同一前缀下有读有写」这类分不开的情况。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicApi {
}
