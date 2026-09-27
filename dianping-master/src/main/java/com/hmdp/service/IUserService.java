package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;


public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    /**
     * 退出登录，传请求头里的登录令牌；令牌为空或已失效都不会报错
     */
    Result logout(String token);

    Result sign();

    Result signCount();
}
