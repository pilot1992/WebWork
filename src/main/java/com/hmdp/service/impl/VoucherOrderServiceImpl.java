package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author flyfish
 * @since 2025-2-20
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 阻塞队列
     *//*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    *//**
     * 处理创建订单的单个线程
     *//*
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try{
                    //从阻塞队列中获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();

                    //创建订单
                    handlerVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理订单异常,{}", e.getMessage());
                }
            }
        }
    }*/

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 消息队列
     */
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try{
                    // 获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 判断消息是否获取成功
                    if(list == null || list.isEmpty()){
                        // 获取失败
                        continue;
                    }

                    // 解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);

                    // 确认ack
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    log.error("处理订单异常,{}", e.getMessage());
                    handlePendingList();
                }
            }
        }

        /**
         * 处理消息队列中异常消息(未确认ack)
         */
        private void handlePendingList() {
            while (true) {
                try{
                    // 获取PendingList中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 判断消息是否获取成功
                    if(list == null || list.isEmpty()){
                        // 获取失败，PendingList中无异常消息
                        break;
                    }

                    // 解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    // 创建订单
                    handleVoucherOrder(voucherOrder);

                    // 确认ack
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    log.error("处理PendingList异常,{}", e.getMessage());
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }


    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();

        //使用redisson创建锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();

        if(!isLock){
            log.error("不允许重复下单");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }

    }


    /**
     * 秒杀优惠券下单
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        /**
         * 通过redis实现秒杀下单
         */
        // 获取用户
        Long userId = UserHolder.getUser().getId();

        // 获取订单id
        long orderId = redisIdWorker.nextId("order");

        /*
          执行lua脚本（阻塞队列版）
         */
        /*Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );*/

        /*
        执行lua脚本（消息队列版）
         */
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        // 判断是否是0
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        // 返回订单id
        return Result.ok(Optional.of(orderId));


        /**
         * 通过阻塞队列实现秒杀下单
         */
        // 为0，有购买资格，把下单信息保存到阻塞队列
        /*VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 保存阻塞队列
        orderTasks.add(voucherOrder);

        proxy = (IVoucherOrderService) AopContext.currentProxy();
*/



        /**
         * 通过数据库实现秒杀下单
         */
        /*//查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //未开始
            return Result.fail("秒杀尚未开始");
        }

        //判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }

        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        //自定义redis锁工具创建锁
        Long userId = UserHolder.getUser().getId();
        *//*SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);

        boolean isLock = lock.tryLock(1200);

        if(!isLock){
            return Result.fail("操作过于频繁");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }*//*

        //使用redisson创建锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();

        if(!isLock){
            return Result.fail("操作过于频繁");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }*/
    }


    /**
     * 在阻塞队列下的创建订单
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getId();
        int count = query().eq("voucher_id", voucherOrder.getVoucherId()).eq("user_id", userId)
                .count();
        if(count > 0){
            log.error("用户重复购买");
            return;
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", Optional.of(0))
                .update();
        if(!success){
            log.error("库存不足");
            return;
        }

        //创建订单
        save(voucherOrder);
    }


    /**
     * 在redis下的创建订单
     * @param voucherId
     * @return
     */
    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId)
                .count();
        if(count > 0){
            return Result.fail("用户重复购买");
        }

        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("库存不足");
        }

        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        //用户id
        voucherOrder.setUserId(userId);

        //订单券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);

        return Result.ok(voucherId);
    }*/
}
