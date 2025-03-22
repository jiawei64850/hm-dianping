package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
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
        // 4. store the code into redis --- set key value ex 120
        // session.setAttribute("code", code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
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
        //String cacheCode = session.getAttribute("code").toString();
        // 1.1 get the code from redis
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
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
        // session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 5. store information of user into redis (whatever is new or not)
        // 5.1 generate token randomly as login authentication
        String token = UUID.randomUUID().toString(true);
        // 5.2 convert user object into hashMap format
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 5.3 store it into redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.4 set the expired date for the redis
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6. return ok
        return Result.ok(token);
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

    public Result logout(HttpServletRequest request) {
        // 1. get user's nickname
        String name = UserHolder.getUser().getNickName();
        // 2. get token from request
        String token = request.getHeader("Authorization");
        // 3. splice the tokenKey
        String tokenKey = LOGIN_USER_KEY + token;
        // 4. delete tokenKey from redis
        stringRedisTemplate.delete(tokenKey);

        log.info("用户: {}, 登陆成功", name);
        UserHolder.removeUser();
        return Result.ok();

    }

    @Override
    public Result sign() {
        // 1. get current user
        Long userId = UserHolder.getUser().getId();
        // 2. get current date
        LocalDateTime now = LocalDateTime.now();
        // 3. splice the string
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. get the current date of mouth
        int dayOfMonth = now.getDayOfMonth();
        // 5. put it into redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1. get current user
        Long userId = UserHolder.getUser().getId();
        // 2. get current date
        LocalDateTime now = LocalDateTime.now();
        // 3. splice the string
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. get the current date of mouth
        int dayOfMonth = now.getDayOfMonth();
        // 5. get the checkin record until the current date of mouth, which is a decimal
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.size() == 0) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. for loop to get the count
        int count = 0;
        while (true) {
            // 6.1 make this number "&" bitwise operation with 1 to get the last digit of it
            // 6.2 check this digit is 0 or not
            if ((num & 1) == 0) {
                // 6.3 0 is non-check-in status
                break;
            } else {
                // 6.4 1 is check-in status
                count++;
            }
            // 6.5 move 1 digit to right side to remove the last digit
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
