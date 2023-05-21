package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

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
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithCacheThrow(id);
        Shop shop = cacheClient.queryWithCacheThrow(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
//        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById);
//        Shop shop = queryWithMutex(id );
//        Shop shop = queryWithLogicDelete(id);
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y), new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (search == null) {
            return Result.ok(Collections.emptyList());
        }
        //截取数据from - end
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = search.getContent();
        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>();
        list.stream().skip(from).forEach(result -> {
                    String shopId = result.getContent().getName();
                    ids.add(Long.valueOf(shopId));
                    Distance distance = result.getDistance();
                    distanceMap.put(shopId, distance);
                }
        );
        //根据获得的id查询商铺
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = StrUtil.join(",", ids);
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
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
        Shop shop = null;
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

    //
    public static final ExecutorService executor = Executors.newFixedThreadPool(5);

    public Shop queryWithLogicDelete(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopString = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopString)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopString, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期
            return shop;
        }
        //过期  更新缓存数据
        //获取锁 看是否有线程开启了更新
        boolean isLock = tryLock(LOCK_SHOP_KEY);
        if (isLock) {
            //拿到了锁 开始更新
            //开启独立线程更新
            executor.submit(() -> {
                if (expireTime.isAfter(LocalDateTime.now())) {
                    //未过期(已经给更新过了  释放锁的同时 会有线程进入 )
                    return;
                }
                try {
                    saveShop2Redis(id, 30L, TimeUnit.SECONDS);
                    System.out.println("缓存重建");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(LOCK_SHOP_KEY);
                }
            });
        }
        return shop;

    }

    public void saveShop2Redis(Long id, Long time, TimeUnit timeUnit) throws InterruptedException {
        //从数据库拿去最新数据存入redis
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData shopRedisData = new RedisData();
        shopRedisData.setData(shop);
        shopRedisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopRedisData));
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
