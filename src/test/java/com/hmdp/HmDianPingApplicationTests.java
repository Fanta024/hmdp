package com.hmdp;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.cron.timingwheel.SystemTimer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import lombok.SneakyThrows;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.runner.RunWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@SpringBootTest
@RunWith(SpringRunner.class)
@AutoConfigureMockMvc
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
    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;

    @Resource
    private ObjectMapper mapper;
    @Test
    public void test3(){
        LocalDateTime localDateTime = LocalDateTime.of(2023, 1, 1, 0, 0, 0, 0);
        long second = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
    @Test
    @SneakyThrows
    @DisplayName("登录1000个用户，并输出到文件中")
    public void login() {
        List<String> phoneList = userService.lambdaQuery()
                .select(User::getPhone)
                .last("limit 1000")
                .list().stream().map(User::getPhone).collect(Collectors.toList());
        ExecutorService executorService = ThreadUtil.newExecutor(phoneList.size());
        List<String> tokenList = new CopyOnWriteArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(phoneList.size());
        phoneList.forEach(phone -> {
            executorService.execute(() -> {
                try {
                    // 验证码
                    String codeJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/code")
                                    .queryParam("phone", phone))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    Result result = mapper.readerFor(Result.class).readValue(codeJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的验证码失败", phone));
                    String code = result.getData().toString();
                    LoginFormDTO loginFormDTO = new LoginFormDTO();
                    loginFormDTO.setCode(code);
                    loginFormDTO.setPhone(phone);
//                        LoginFormDTO formDTO = LoginFormDTO.builder().code(code).phone(phone).build();

                    String json = mapper.writeValueAsString(loginFormDTO);
                    // token
                    String tokenJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/login").content(json).contentType(MediaType.APPLICATION_JSON))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    result = mapper.readerFor(Result.class).readValue(tokenJson);
                    System.out.println(result);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的token失败,json为“%s”", phone, json));
                    String token = result.getData().toString();
                    tokenList.add(token);
                    countDownLatch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
        countDownLatch.await();
        executorService.shutdown();
        Assert.isTrue(tokenList.size() == phoneList.size());
        writeToTxt(tokenList, "\\tokens.txt");
        System.out.println("写入完成！");
    }

    private static void writeToTxt(List<String> list, String suffixPath) throws Exception {
        // 1. 创建文件
        File file = new File(System.getProperty("user.dir") + "\\src\\main\\resources" + suffixPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        // 2. 输出
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        for (String content : list) {
            bw.write(content);
            bw.newLine();
        }
        bw.close();
        System.out.println("写入完成！");
    }
}
