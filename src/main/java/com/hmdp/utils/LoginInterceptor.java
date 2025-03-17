package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. get the session from request
        HttpSession session = request.getSession();
        // 2. get the user from session
        Object user = session.getAttribute("user");
        // 3. check if user exists or not
        if (user == null) {
            // 4. intercept it if not
            response.setStatus(401);
            return true;
        }
        // 5. store user into ThreadLocal if it does
        UserHolder.saveUser((UserDTO) user);
        // 6. pass request
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
