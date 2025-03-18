package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. check should intercept or not (get user from ThreadLocal)
        if (UserHolder.getUser() == null) {
            // 2. intercept it and set the status code if not
            response.setStatus(401);
            return true;
        }
        // 3. pass request if it does
        return true;
    }

}
