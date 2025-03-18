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
    @Transactional
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
        // 5. subtract from the stock
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 6. create order
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1 set order id
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2 set user id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3 set voucher id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7. return
        return Result.ok(orderId);
    }
}
