package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.MqConstants;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 18/03/2025
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedissonClient redissonClient;

    // public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    public static final String queueName = "stream.orders";


    private void handlePendingList(){
        while (true) {
            try {
                // 1. get the info of voucherOrder from pending list
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                // 2. check the result is successful or not
                if (list == null || list.isEmpty()) {
                    // 2.1 break while loop if not (no abnormal message)
                    break;
                }
                // 3 create the order if it does
                // 3.1 parse the info of order from msg list
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                // 4. ack
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.info("处理pending-list异常", e);
                try {
                    Thread.sleep(50);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }
    @Scheduled(fixedRate = 10000) // scanning pending-list every 10s
    public void checkPendingList() {
        log.debug("正在扫描 Redis Stream pending-list 补偿消息...");
        handlePendingList();
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        if (proxy == null) {
            proxy = (IVoucherOrderService) AopContext.currentProxy();
        }
        // 1. get the user id from voucher order
        Long userId = voucherOrder.getUserId();
        // 2. create the object of mutex
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // get the mutex
        boolean isLock = lock.tryLock();
        // check succeed or not
        if (!isLock) {
            log.error("不能重复下单！");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // release the mutex
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextId("order:");
        // 1. execute luas script
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2. check if the result of script is 0 or not
        int r = result.intValue();
        if (r != 0) {
            // 2.1 not 0, have no purchase access
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 2.1 get the proxy object (Redis Stream - version 3)
        // proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 2.2 construct and send message to RabbitMQ
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setVoucherId(voucherId);
        message.setUserId(userId);
        rabbitTemplate.convertAndSend(MqConstants.ORDER_EXCHANGE, MqConstants.ORDER_ROUTING_KEY, message);
        // 3 return
        return Result.ok(orderId);
    }
    /*
    version 0: (without lock, without Concurrency Control, without transactional proxy)
    public Result seckillVoucher(Long voucherId) {
        // 1. query the voucher
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. check if seckill start or not
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }

        // 3. check if seckill end or not
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 4. check if stock is enough or not
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        // 4. create order（synchronous invoke）
        VoucherOrder voucherOrder = new VoucherOrder();
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder); // without lock, Concurrency Control, and transactional proxy

        return Result.ok(orderId);
    }
     */
    /* version 1: Add Distributed Lock + Transaction Proxy + Duplicate Check (synchronous invoke)
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // early stage: JVM lock - synchronized + intern()
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        // middle stage: SimpleRedisLock - simple version of RedissonLock
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // final stage: RedissonLock
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if (!isLock) {
            return Result.fail("不能重复下单！");
        }

        try {
            // ✅ Proxy ensures @Transactional works
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }
    */

    /* version 2: Add Distributed Lock + Transaction Proxy + Duplicate Check (asynchronous invoke)
    --> Producer:
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString()
        );  // execute luas script

        int r = result.intValue();
        if (r != 0) {
            // not 0, have no purchase access
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        // have purchase access, put the order info into blocking queue
        long orderId = redisIDWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder); // ✅ local BlockingQueue

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    --> Consumer
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    public class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTasks.take(); // ❗ blocking
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder order) {
        RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不能重复下单！");
            return;
        }

        try {
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }
    */

    /* version 3:  Async with Redis Stream (Consumer Group + PendingList Recovery)
    --> Producer:
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextId("order:");

        Long result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        if (result != 0) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

        // Lua executed successfully — order info is pushed into Redis Stream via Lua script
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    --> Consumer
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    public class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. get the info of voucherOrder from message list
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2. check the result is successful or not
                    if (list == null || list.isEmpty()) {
                        // 2.1 continue next while loop if not (no abnormal message)
                        continue;
                    }
                    // 3 create the order if it does
                    // 3.1 parse the info of order from msg list
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4. ack
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList(); // ✅ retry un-ACKed
                }
            }
        }
    }
    */


    // version 2 - 4;
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5 ensure same user only get the one voucher
        // 5.1 query the order by userId and voucherId
        Long userId = voucherOrder.getUserId();
        Integer count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder)
                .count();
        // 5.2 check the order exists or not
        if (count > 0) {
            log.error("用户已经购买过了！");
            return;
        }
        // 6. subtract from the stock
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        // 7. create order
        save(voucherOrder);
    }

    /*
    version 1:
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("不能重复下单！");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (!success) {
            return Result.fail("库存不足！");
        }

        // synchronous invoke
        VoucherOrder order = new VoucherOrder();
        order.setId(redisIDWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        save(order);

        return Result.ok(order.getId());
    }
    */
}
