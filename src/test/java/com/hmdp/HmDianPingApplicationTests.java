package com.hmdp;

import cn.hutool.cron.timingwheel.SystemTimer;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {
//    @Resource
//    private ShopServiceImpl shopService;
//    @Resource
//    private RedisIdWorker redisIdWorker;
//
//    private ExecutorService executorService= Executors.newFixedThreadPool(50);
//    @Test
//    public void test(){
////        shopService.saveShop2Redis(1L,10L,TimeUnit.SECONDS);
//        shopService.queryWithLogicDelete(1L);
//    }
//    @Test
//    public void test2() throws InterruptedException {
//        CountDownLatch countDownLatch = new CountDownLatch(50);
//        Runnable task=()->{
//            for (int i = 0; i < 50; i++) {
//                long a = redisIdWorker.nexId("a");
//                System.out.println(a);
//            }
//            countDownLatch.countDown();
//        };
//        long begin = System.currentTimeMillis();
//        for (int i = 0; i < 100; i++) {
//            executorService.submit(task);
//        }
//        countDownLatch.await();
//        long end = System.currentTimeMillis();
//        System.out.println(begin-end);
//    }
    @Test
    public void test3(){
        LocalDateTime localDateTime = LocalDateTime.of(2023, 1, 1, 0, 0, 0, 0);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
