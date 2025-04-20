package com.hmdp.mq;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MqConstants;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
@Component
@Slf4j
public class VoucherOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConstants.ORDER_QUEUE)
    public void handleMessage(VoucherOrderMessage message, Channel channel, Message mqMsg) throws IOException {
        Long userId = message.getUserId();
        if (message.getUserId() == 999L) { // 👈 模拟特定用户抛异常
            throw new RuntimeException("模拟异常：处理失败");
        }
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不能重复下单！");
            channel.basicAck(mqMsg.getMessageProperties().getDeliveryTag(), false);
            return;
        }

        try {
            VoucherOrder order = new VoucherOrder();
            order.setId(message.getOrderId());
            order.setUserId(userId);
            order.setVoucherId(message.getVoucherId());
            voucherOrderService.createVoucherOrder(order);

            channel.basicAck(mqMsg.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            log.error("处理订单异常", e);
            channel.basicNack(mqMsg.getMessageProperties().getDeliveryTag(), false, false);
        } finally {
            lock.unlock();
        }
    }

    @RabbitListener(queues = MqConstants.DEAD_QUEUE)
    public void handleDead(VoucherOrderMessage message) {
        String key = "dead:order:" + message.getOrderId();
        Boolean added = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(10));
        if (Boolean.FALSE.equals(added)) {
            log.info("该死信订单已处理过，跳过");
            return;
        }

        Map<String, String> map = new HashMap<>();
        map.put("orderId", message.getOrderId().toString());
        map.put("userId", message.getUserId().toString());
        map.put("voucherId", message.getVoucherId().toString());

        stringRedisTemplate.opsForStream().add("stream.orders", map);
    }
}
