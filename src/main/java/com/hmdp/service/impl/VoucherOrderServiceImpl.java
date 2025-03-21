package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
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

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedissonClient redissonClient;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    public class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. get the info of voucherOrder from list
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. create the order
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.info("处理订单异常", e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
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
        // 1. execute luas script
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        // 2. check if the result of script is 0 or not
        int r = result.intValue();
        if (r != 0) {
            // 2.1 not 0, have no purchase access
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2 0, have purchase access, put the order info into blocking queue
        long orderId = redisIDWorker.nextId("order:");
        // TODO blocking queue
        // 2.3. create order
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.4 set order id
        voucherOrder.setId(orderId);
        // 2.5 set user id
        voucherOrder.setUserId(userId);
        // 2.6 set voucher id
        voucherOrder.setVoucherId(voucherId);
        // 2.7 add it into blocking queue
        orderTasks.add(voucherOrder);
        // 2.8 get the proxy object
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3 return
        return Result.ok(orderId);
    }
//    public Result seckillVoucher(Long voucherId) {
//        // 1. query the voucher
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2. check if seckill start or not
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3. check if seckill end or not
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4. check if stock is enough or not
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            // get the proxy object (transactional)
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        // create the object of mutex
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
//        // get the mutex
//        boolean isLock = lock.tryLock();
//        // check succeed or not
//        if (!isLock) {
//            return Result.fail("不能重复下单！");
//        }
//        try {
//            // get the proxy object (transactional)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // release the mutex
//            lock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 5 ensure same user only get the one voucher
//        // 5.1 query the order by userId and voucherId
//        Long userId = UserHolder.getUser().getId();
//        Integer count = query()
//                .eq("user_id", userId)
//                .eq("voucher_id", voucherId)
//                .count();
//        // 5.2 check the order exists or not
//        if (count > 0) {
//            return Result.fail("用户已经购买过了！");
//        }
//        // 6. subtract from the stock
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if (!success) {
//            return Result.fail("库存不足！");
//        }
//        // 7. create order
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 7.1 set order id
//        long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 7.2 set user id
//
//        voucherOrder.setUserId(userId);
//        // 7.3 set voucher id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        // 8. return
//        return Result.ok(orderId);
//    }

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
}
