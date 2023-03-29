package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key=CACHE_SHOPTYPE_KEY;
        List<String> queryList = stringRedisTemplate.opsForList().range(key, 0, -1);
        System.out.println(queryList);

        if(CollectionUtil.isNotEmpty(queryList)){
            List<ShopType> shopTypes = new ArrayList<>();
            queryList.forEach(item->{
                shopTypes.add(JSONUtil.toBean(item,ShopType.class));
            });
            System.out.println(shopTypes);
            return Result.ok(shopTypes);
        }
        //不存在 创建
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
        System.out.println(shopTypeList);
        if(CollectionUtil.isEmpty(shopTypeList)){
            return Result.fail("查找失败");
        }

        ArrayList<String> arrayList = new ArrayList<>();
        shopTypeList.forEach(item->{
            arrayList.add(JSONUtil.toJsonStr(item));
        });
        System.out.println("arrayList===="+arrayList);
        stringRedisTemplate.opsForList().rightPushAll(key,arrayList);

        return Result.ok(arrayList);
    }
}
