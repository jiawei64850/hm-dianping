package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author jiawei
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1. validate the phone number
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. return error message if not
            return Result.fail("手机号格式错误！");
        }
        // 3. generate validation code if it does
        String code = RandomUtil.randomNumbers(6);
        // 4. store the code into session
        session.setAttribute("code", code);
        // 5. send the code
        log.debug("发送短信验证码成功, 验证码: {}", code);
        // 6. return ok
        return Result.ok();
    }

    /**
     * 用户登陆
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. validate phone and code
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        String cacheCode = session.getAttribute("code").toString();
        String code = loginForm.getCode();
        if (cacheCode == null || !code.equals(cacheCode)) {
            // 2. return error message if not
            return Result.fail("验证码错误！");
        }
        // 3. query the user whether in database or not (if validation pass)
        User user = query().eq("phone", phone).one();
        // 4. register to new user if not exist in database
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // 5. store information of user into session (whatever is new or not)
        session.setAttribute("user", user);
        // 6. return ok
        return Result.ok();
    }

    /**
     * 根据手机号注册新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 1. create user object
        User user = new User();
        // 2. set the attribute
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 3. store the user into database
        save(user);
        return user;
    }
}
