package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker idWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀还未开始");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
        if(voucher.getStock()<1){
            return Result.fail("已抢完");
        }
        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("VoucherOrder:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("VoucherOrder:" + userId);
//        boolean isLock = simpleRedisLock.tryLock(1000L);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("重复下单");
        }
        //userId.toString() toString本质是new String 每次的对象会不一样 加上intern才能保证一致
        //锁要包裹事务，事务不能包裹锁
//        synchronized (userId.toString().intern())
        try {
            //获取代理对象
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            return currentProxy.createVoucher(voucherId);
            //return createVoucher(voucherId); 事务会失效  因为 用的是this.createVoucher  而IVoucherOrderService没有事务
        }catch (Exception e){
            throw e;
        }finally {
//            simpleRedisLock.unLock();
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(count>0){
            return Result.fail("已抢过优惠券");
        }
        boolean rs = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId)
                        .gt("stock",0).update();
        if(!rs){
            return Result.fail("已抢完");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = idWorker.nexId("voucher");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        return Result.ok(orderId);
    }
}
