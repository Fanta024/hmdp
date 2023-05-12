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
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String key = LOGIN_CODE_KEY + phone;
        String code = RandomUtil.randomNumbers(6);
        String s = stringRedisTemplate.opsForValue().get(key);
        System.out.println( "已存在验证码="+s);
        if(s==null){
            System.out.println("设置验证码"+code);
            stringRedisTemplate.opsForValue().set(key,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        }
        else{
            code=s;
        }
        //发送验证码
        System.out.println("验证码"+code);
        return Result.ok(code);
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        User user = new User();
        if (loginForm.getPassword() != null) {
            user = query().eq("phone", loginForm.getPhone()).eq("password", loginForm.getPassword()).one();
        }
        if (loginForm.getCode() != null) {
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
            String code = loginForm.getCode();
            if (cacheCode == null || !cacheCode.equals(code)) {
                // 不一致，报错
                return Result.fail("验证码错误");
            }
            user = query().eq("phone", loginForm.getPhone()).one();
        }
        //用户不存在创建用户 并保存登录状态
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }
        //生成token 作为登录令牌
        String token = UUID.randomUUID().toString();
        //将脱敏的user存入redis
        //脱敏
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将user转为map
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((k, v) -> {
                            System.out.println(k + "===" + v);
                            return v.toString();
                        }
                ));
        //存储
        String tokenKey = "login:token:" + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置过期时间
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //返回token给前端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
