package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithCacheThrow(id);


        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商店不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop == null) {
            return Result.fail("商店id不能为空");
        }
        //更新数据库
        this.updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }


    //缓存穿透
    public Shop queryWithCacheThrow(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(data)) {
            return JSONUtil.toBean(data, Shop.class);
        }
        if (Objects.equals(data, "")) {
            return null;
        }

        Shop shop = this.getById(id);
        if (shop == null) {
            //存入空值到redis 解决缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String data = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(data)) {
            return JSONUtil.toBean(data, Shop.class);
        }
        if (Objects.equals(data, "")) {
            return null;
        }
        //加锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop=null;
        try {
            boolean isLock = tryLock(lockKey);
            System.out.println(isLock);
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            shop = this.getById(id);
            if (shop == null) {
                //存入空值到redis 解决缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //解锁
            unlock(lockKey);
        }

        return shop;
    }

    /**
     * @param lockKey
     * @return true 没有锁  false 有锁
     */
    public boolean tryLock(String lockKey) {
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(lock);
    }

    public void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
