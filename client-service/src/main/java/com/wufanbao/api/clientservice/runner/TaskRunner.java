package com.wufanbao.api.clientservice.runner;

import com.wufanbao.api.clientservice.common.CommonFun;
import com.wufanbao.api.clientservice.common.rabbitMQ.RabbitMQSender;
import com.wufanbao.api.clientservice.service.UserService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

@Component
@EnableScheduling
public class TaskRunner {
    @Autowired
    private UserService userService;
    @Autowired
    private CommonFun commonFun;
    @Autowired
    private RabbitMQSender rabbitMQSender;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    /**
     * 每隔5秒执行一次：/5 * * * ?
     * 每隔1分钟执行一次：0 /1 * * ?
     * 每天23点执行一次：0 0 23 * * ?
     * 每天凌晨1点执行一次：0 0 1 * * ?
     * 每月1号凌晨1点执行一次：0 0 1 1 * ?
     * 每月最后一天23点执行一次：0 0 23 L * ?
     * 每周星期天凌晨1点实行一次：0 0 1 ? * L
     * 在26分、29分、33分执行一次：0 26,29,33 * * * ?
     * 每天的0点、13点、18点、21点都执行一次：0 0 0,13,18,21 * * ?
     * 每天的8点开始，2小时执行一次：0 0 8/2 * * ?
     */

 /*   @Scheduled(cron = "0/5 * * * * ?")
    public void test1(){
        String message="hello帅哥!";
        String userOrderId="25909771075585";
        JSONObject jsonObjectMesage=new JSONObject();
        String userOrderIdStr=String.valueOf(userOrderId);
        jsonObjectMesage.put("userOrderIdStr",userOrderIdStr);
        try {
            rabbitMQSender.sendTakeFood(jsonObjectMesage);
        }catch (Exception e){
            e.printStackTrace();
        }

//        amqpTemplate.convertAndSend("zhangShuaiTestQueue", "", String.valueOf(message).getBytes());

    }*/

    //优惠券过期
    @Scheduled(cron = "0/20 * * * * ?")
    public void InvodeOrder() {
        try {
            userService.invalidUserCoupon();
        } catch (Exception ex) {
            logger.error(commonFun.getStackTraceInfo(ex));
        }
    }

    //每日限额，在凌晨一点清除限额
    @Scheduled(cron = "0 0 1 * * ?")
    public void FamilyLimitByDay() {
        try {
            userService.clearFamilyLimitByDay();
        } catch (Exception ex) {
            logger.error(commonFun.getStackTraceInfo(ex));
        }
    }

    //每月限额，在每月凌晨一点清除限额
    @Scheduled(cron = "0 0 1 1 * ?")
    public void FamilyLimitByMonth() {
        try {
            userService.clearFamilyLimitByMonth();
        } catch (Exception ex) {
            logger.error(commonFun.getStackTraceInfo(ex));
        }
    }
}
