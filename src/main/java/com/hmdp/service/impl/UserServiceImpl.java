package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
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

    @Override
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
}
