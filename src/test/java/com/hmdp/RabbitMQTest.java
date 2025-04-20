package com.hmdp;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.utils.MqConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@SpringBootTest
@RunWith(SpringRunner.class)
public class RabbitMQTest {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Test
    public void testSend() {
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(123456789L);
        message.setUserId(1001L);
        message.setVoucherId(1L);

        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_EXCHANGE,
                MqConstants.ORDER_ROUTING_KEY,
                message
        );
    }


    // it should revise the code to simulate the case of exception at comsumer
    @Test
    public void testDeadLetterQueue() {
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(123456789L);
        message.setUserId(999L);
        message.setVoucherId(1L);

        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_EXCHANGE,
                MqConstants.ORDER_ROUTING_KEY,
                message
        );

        System.out.println("消息已发送，检查死信队列日志...");
    }
}
