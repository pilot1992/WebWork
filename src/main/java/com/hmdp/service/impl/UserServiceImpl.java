package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.constant.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.constant.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author flyfish
 * @since 2025-2-20
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送验证码成功，验证码为: {}", code);

        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        String code = loginForm.getCode();
        if(!code.equals(cacheCode)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();    //MyBatisPlus

        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }

        //7.保存用户信息到redis中
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();

        //将User对象转成HashMap储存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        //2.保存用户
        save(user); //MyBatisPlus
        return user;
    }
}
