package com.hmdp;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import lombok.Builder;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import jakarta.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@SpringBootTest
@AutoConfigureMockMvc
class VoucherOrderControllerTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;

    @Resource
    private ObjectMapper mapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    /**
     * <p>
     * 这是<b>手动执行</b>的压测准备工具，不是单元测试，所以默认跳过。
     * </p>
     * <p>
     * 它会真实登录 1000 个用户，在 Redis 里写入 1000 个 {@code login:token:*}
     * （TTL 30 分钟），并生成 {@code target/tokens.txt} 供后续秒杀压测使用。
     * 放在每次 {@code mvn test} 里跑有两个问题：
     * </p>
     * <ul>
     *   <li>每跑一次就往 Redis 塞 1000 个 key，跑几次就是几千个；</li>
     *   <li>它产出的 token 本来就该留着给压测用，自动跑完即弃没有意义。</li>
     * </ul>
     * <p>
     * 需要用的时候，去掉 {@code @Disabled} 或指定方法名单独执行。
     * </p>
     */
    @Disabled("压测准备工具：会写 1000 个 login:token:* 到 Redis，按需手动执行")
    @Test
    @SneakyThrows
    @DisplayName("登录1000个用户，并输出到文件中")
    void login() {
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
                    // 验证码：/user/code 出于安全考虑不会把验证码放进响应体，也不打日志，
                    // 所以必须从 Redis 取。读 result.getData() 永远拿到 null。
                    String codeJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/code")
                                    .queryParam("phone", phone))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    Result result = mapper.readerFor(Result.class).readValue(codeJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的验证码失败", phone));
                    String code = stringRedisTemplate.opsForValue()
                            .get(RedisConstants.LOGIN_CODE_KEY + phone);
                    Assert.notBlank(code, String.format("Redis 里没有“%s”手机号的验证码", phone));
                    LoginFormDTO formDTO = LoginFormDTO.builder().code(code).phone(phone).build();
                    String json = mapper.writeValueAsString(formDTO);
                    // token
                    String tokenJson = mockMvc.perform(MockMvcRequestBuilders
                                    .post("/user/login").content(json).contentType(MediaType.APPLICATION_JSON))
                            .andExpect(MockMvcResultMatchers.status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    result = mapper.readerFor(Result.class).readValue(tokenJson);
                    Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的token失败,json为“%s”", phone, json));
                    String token = result.getData().toString();
                    tokenList.add(token);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 必须无条件倒计数。一旦上面抛异常而没走到这里，
                    // 下面的 countDownLatch.await() 会永久阻塞，把整个测试套件挂死。
                    countDownLatch.countDown();
                }
            });
        });
        countDownLatch.await();
        executorService.shutdown();
        Assert.isTrue(tokenList.size() == phoneList.size());
        writeToTxt(tokenList, "tokens.txt");
        System.out.println("写入完成！");
    }

    private static void writeToTxt(List<String> list, String fileName) throws Exception {
        // 写到 target/ 下，不要污染 src/main/resources
        File dir = new File(System.getProperty("user.dir"), "target");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + dir);
        }
        File file = new File(dir, fileName);
        if (!file.exists() && !file.createNewFile()) {
            throw new IllegalStateException("无法创建文件：" + file);
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