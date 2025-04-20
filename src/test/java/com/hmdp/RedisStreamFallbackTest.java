package com.hmdp;

import com.hmdp.service.IVoucherOrderService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.aop.framework.AopContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@SpringBootTest
@RunWith(SpringRunner.class)
public class RedisStreamFallbackTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Test
    public void testAddToPendingList() {
        // 模拟写入一条未ACK的订单消息（手动添加 pending）
        Map<String, String> order = new HashMap<>();
        order.put("userId", "10086");
        order.put("voucherId", "1");
        order.put("id", "202504200001L");

        stringRedisTemplate.opsForStream()
                .add(StreamRecords.mapBacked(order).withStreamKey("stream.orders"));
        System.out.println("消息已写入 Redis Stream");
    }

    @Test
    public void testRecoverPending() {
        voucherOrderService.checkPendingList();
        System.out.println("已调用 pending-list 补偿逻辑");
    }
}
