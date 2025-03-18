package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author jiawei
 * @since 18/03/2025
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Override

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
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            // get the proxy object (transactional)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5 ensure same user only get the one voucher
        // 5.1 query the order by userId and voucherId
        Long userId = UserHolder.getUser().getId();
        Integer count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        // 5.2 check the order exists or not
        if (count > 0) {
            return Result.fail("用户已经购买过了！");
        }
        // 6. subtract from the stock
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 7. create order
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 set order id
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 set user id

        voucherOrder.setUserId(userId);
        // 7.3 set voucher id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 8. return
        return Result.ok(orderId);


    }
}
