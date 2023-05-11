package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker idWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    public static final String queueName = "stream.orders";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列的订单 xreadgroup group g1 c1 count 1 block 2000 streams s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //处理成功 确认消息 xack key grop id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //未确认
                    pendingListHandler();
                }
            }
        }

        private void pendingListHandler() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        //没有消息
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //获取成功 可以下单
                    handleVoucherOrder(voucherOrder);
                    //处理成功 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("pendList处理异常");
                    //休眠一段时间再进入循环避免频繁
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /**
     * 创建订单
     * 获取阻塞队列里的订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 开启了新的线程 所以从ThreadLocal取不到userid
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("VoucherOrder:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("重复下单");
        }
        try {
            //子线程拿不到主线程的代理对象  所以需要将代理对象在主线程时获取  然后提取成成员变量 子线程就可使用  或存入消息队列中子线程再获取
            //获取代理对象（开启事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

            proxy.createVoucher4VoucherOrder(voucherOrder);
            //return createVoucher(voucherId); 事务会失效  因为 用的是this.createVoucher  而IVoucherOrderService没有事务
        } catch (Exception e) {
            throw e;
        } finally {
//            simpleRedisLock.unLock();
            lock.unlock();
        }
    }

    //秒杀优化
    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("已抢完");
        }
        //有购买资格 创建订单放入消息队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = idWorker.nexId("order");
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long rs = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = rs.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "重复下单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public Result createVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("已抢过优惠券");
        }
        boolean rs = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if (!rs) {
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

    @Transactional
    public void createVoucher4VoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            log.error("已抢过优惠券");
            return;
        }
        boolean rs = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if (!rs) {
            log.error("已抢完");
            return;
        }

        //创建订单
        save(voucherOrder);

    }

    //    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀还未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//        if(voucher.getStock()<1){
//            return Result.fail("已抢完");
//        }
//        Long userId = UserHolder.getUser().getId();
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("VoucherOrder:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("VoucherOrder:" + userId);
////        boolean isLock = simpleRedisLock.tryLock(1000L);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("重复下单");
//        }
//        //userId.toString() toString本质是new String 每次的对象会不一样 加上intern才能保证一致
//        //锁要包裹事务，事务不能包裹锁
////        synchronized (userId.toString().intern())
//        try {
//            //获取代理对象
//            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//            return currentProxy.createVoucher(voucherId);
//            //return createVoucher(voucherId); 事务会失效  因为 用的是this.createVoucher  而IVoucherOrderService没有事务
//        }catch (Exception e){
//            throw e;
//        }finally {
////            simpleRedisLock.unLock();
//            lock.unlock();
//        }
//    }

}
