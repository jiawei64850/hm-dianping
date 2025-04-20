package com.hmdp.utils;

public class MqConstants {

    // order creating queue
    public static final String ORDER_EXCHANGE = "voucher.order.exchange";
    public static final String ORDER_QUEUE = "voucher.order.queue";
    public static final String ORDER_ROUTING_KEY = "voucher.order";

    // dead letter queue
    public static final String DEAD_EXCHANGE = "voucher.order.dead.exchange";
    public static final String DEAD_QUEUE = "voucher.order.dead.queue";
    public static final String DEAD_ROUTING_KEY = "voucher.order.dead";
}
