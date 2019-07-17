package com.wufanbao.api.clientservice.common.rabbitMQ;

import com.wufanbao.api.clientservice.common.*;
import com.wufanbao.api.clientservice.dao.MessageDao;
import com.wufanbao.api.clientservice.entity.Machine;
import com.wufanbao.api.clientservice.entity.UserOrder;
import com.wufanbao.api.clientservice.entity.UserOrderLine;
import com.wufanbao.api.clientservice.service.*;
import org.aspectj.weaver.ast.Var;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.util.*;

@Component
public class RabbitMQReceiver {

    @Autowired
    private CommonFun commonFun;
    @Autowired
    private RedisUtils redisUtils;
    @Autowired
    private UserOrderService userOrderService;
    @Autowired
    private MachineService machineService;
    @Autowired
    private UserService userService;
    @Autowired
    private MessageService messageService;
    @Autowired
    protected ResponseData responseData;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final String templateIdTake = "PSGgOqrlQCbI70c33qP48A-UTUGLMrL_4hTj7cdNTn0";

    //用户取餐
    @RabbitListener(queues = "#{OrderFetchCompletedQueue.name}")
    public void takeFood(byte[] body) {
        String message = null;
        try {
            message = new String(body, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.info("用户取餐推送异常");
            logger.error(commonFun.getStackTraceInfo(e));
        }
        String userOrderIdStr = message.substring(11, message.length() - 1);
        System.out.println(userOrderIdStr);
        long userOrderId = Long.parseLong(userOrderIdStr);
        UserOrder userOrder = userOrderService.findUserOrder(userOrderId);
        int originateid = userOrder.getOriginateId();
        if (originateid == 4) {
            Machine machine = machineService.getMachine(userOrder.getMachineId());
            //机器名称
            String machineName = machine.getPutMachineName();
            List<UserOrderLine> userOrderLines = userOrderService.getUserOrderLine(userOrderId);
            //出餐数量
            int quantityint = 0;
            for (UserOrderLine userOrderLine : userOrderLines) {
                quantityint += userOrderLine.getQuantity();
            }
            long userId = userOrder.getUserId();
            String openId = userService.getOpenId(userId);
            String quantity = String.valueOf(quantityint);
            try {
                //消息推送
                userOrderService.sendTemplateTake(userOrderId, openId, quantity, machineName, templateIdTake);
                logger.info("取餐提醒推送成功，订单" + userId);
            } catch (Exception ex) {
                logger.info("取餐提醒推送失败，订单" + userId);
                logger.error(commonFun.getStackTraceInfo(ex));
            }
        }
        //APP内支付  暂时不用
        if (originateid == 1 || originateid == 2 || originateid == 3) {
            Map<String, String> param = new HashMap<>();
            String mb=userOrderService.getUserMb(userOrderId);
            param.put("id", mb);
            String userOrderIdstr = String.valueOf(userOrderId);
            param.put("msg", "订单" + userOrderIdstr + "已成功取餐");
            param.put("title", "订单完成提醒");
            param.put("type","2");
            param.put("userOrderId", userOrderIdstr);
            try {
                messageService.jpushAll(param);
                logger.info("订单信息提醒推送成功，订单" + userOrderIdstr);
            } catch (Exception ex) {
                logger.info("订单信息提醒推送失败，订单" + userOrderIdstr);
                logger.error(commonFun.getStackTraceInfo(ex));
            }
        }
    }

    @RabbitListener(queues = "#{userpayBindQueue.name}")
    public void userpayBindSend(byte[] body) {
        try {
            String message = new String(body, "UTF-8");
            Map<String, Object> objectMap = JsonUtils.GsonToMaps(message);
            String userId=String.valueOf(objectMap.get("userId"));
            String mb=String.valueOf(objectMap.get("mb"));
            Map<String, String> param = new HashMap<>();
            param.put("id",mb);
            param.put("msg","饭票有了，任意吃");
            param.put("title","开通亲密付");
            param.put("type","3");
            param.put("userId",userId);
            messageService.jpushAll(param);
            logger.info("亲密付消息推送成功"+mb);
        } catch (Exception e) {
            logger.info("亲密付绑定推送异常！");
            logger.error(commonFun.getStackTraceInfo(e));
        }
    }

    @RabbitListener(queues = "#{userpayUnBindQueue.name}")
    public void userpayUnBindSend(byte[] body) {
        try {
            String message = new String(body, "UTF-8");
            Map<String, Object> objectMap = JsonUtils.GsonToMaps(message);
            String userId=String.valueOf(objectMap.get("userId"));
            String mb=String.valueOf(objectMap.get("mb"));
            Map<String, String> param = new HashMap<>();
            param.put("id",mb);
            param.put("msg","伤害了一个爱你的人");
            param.put("title","解除亲密付");
            param.put("type","0");
            param.put("userId",userId);
            messageService.jpushAll(param);
            logger.info("亲密付消息推送成功"+mb);
        } catch (Exception e) {
            logger.info("亲密付绑定推送异常！");
        }
    }

    //优惠券
    @RabbitListener(queues = "#{userCouponEx6Queue.name}")
    public void UserCouponEx6(byte[] body) {
        String content="今日即将过期";
        try {
            String message = new String(body, "UTF-8");
            JSONObject jsonObject = new JSONObject(message);
            String userId = String.valueOf(jsonObject.get("userId"));
            String couponName = String.valueOf(jsonObject.getString("couponName"));
            messageService.insertMessage(userId,couponName,content);
        } catch (Exception ex) {
            logger.error(commonFun.getStackTraceInfo(ex));
        }
    }
    @RabbitListener(queues = "#{refundMoneyQueue.name}")
    public void refundMoney(byte[] body){
        String content="款项已原路返回";
        try {
            String message = new String(body,"UTF-8");
            JSONObject jsonObject=new JSONObject(message);
            String userId=String.valueOf(jsonObject.get("userId"));
            String userOrderId="订单"+String.valueOf(jsonObject.get("userOrderId"));
            messageService.insertMessage(userId,userOrderId,content);
        } catch (Exception e) {
            logger.error(commonFun.getStackTraceInfo(e));
        }
    }

//    //邀请用户奖励
//    @RabbitListener(queues = "#{userFisrtOrderQueue.name}")
//    public void UserFisrtOrder(byte[] body) {
//        try {
//            String message = new String(body, "UTF-8");
//            JSONObject jsonObject = new JSONObject(message);
//            String orderId = jsonObject.getString("orderId");
//            Map map = new HashMap();
////            UserInvitation userInvitation=userCouponDao.getUserInvitation(Long.valueOf(orderId));
////            long rewardId= IDGenerator.generateById("rewardId",userInvitation.getUserId());
////            long invitationId=userCouponDao.getInvitationId(userInvitation.getInvitedUserId(),userInvitation.getUserId());
////            BigDecimal value= new BigDecimal("20.000000");
////            UserReward userReward=new UserReward();
////            userReward.setRewardId(rewardId);
////            userReward.setUserId(userInvitation.getUserId());
////            userReward.setSourceType("userInvitation");
////            userReward.setSourceId(invitationId);
////            userReward.setCreateTime(new Date());
////            userReward.setRewardType(2);
////            userReward.setRewardValue(value);
////            userReward.setReward("20元");
////            if (userInvitation==null){
////                map.put("returnState",0);
////            }else{
////                int x=0;
////                for (int i = 0; i <4 ; i++) {
////                    int count=getUserCoupon(233838379008l,userInvitation.getUserId());
////                    x=x+count;
////                }
////                System.out.println(x);
////                if (x==8){
////                    userCouponDao.insertUserReward(userReward);
////                    map.put("returnState",1);
////                }else{
////                    map.put("returnState",2);
////                }
////            }
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//
//        /*ConnectionFactory factory = new ConnectionFactory();
//        factory.setHost(host);
//        factory.setUsername(username);
//        factory.setPassword(password);
//        factory.setPort(5672);
//        Connection connection = factory.newConnection();
//        Channel channel = connection.createChannel();
//        channel.exchangeDeclare(inviteRewards, "fanout");
//        //产生一个随机的队列名称
//        String queueName = channel.queueDeclare().getQueue();
//        channel.queueBind(queueName, inviteRewards, "");//对队列进行绑定
//        System.out.println("邀请用户奖励");
//        Consumer consumer = new DefaultConsumer(channel) {
//            @Override
//            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
//                String message = new String(body, "UTF-8");
//                JSONObject jsonObject = new JSONObject(message);
//                String orderId = jsonObject.getString("orderId");
//                Map map=new HashMap();
//                UserInvitation userInvitation=userCouponDao.getUserInvitation(Long.valueOf(orderId));
//                long rewardId= IDGenerator.generateById("rewardId",userInvitation.getUserId());
//                long invitationId=userCouponDao.getInvitationId(userInvitation.getInvitedUserId(),userInvitation.getUserId());
//                BigDecimal value= new BigDecimal("20.000000");
//                UserReward userReward=new UserReward();
//                userReward.setRewardId(rewardId);
//                userReward.setUserId(userInvitation.getUserId());
//                userReward.setSourceType("userInvitation");
//                userReward.setSourceId(invitationId);
//                userReward.setCreateTime(new Date());
//                userReward.setRewardType(2);
//                userReward.setRewardValue(value);
//                userReward.setReward("20元");
//                if (userInvitation==null){
//                    map.put("returnState",0);
//                }else{
//                    int x=0;
//                    for (int i = 0; i <4 ; i++) {
//                        int count=getUserCoupon(233838379008l,userInvitation.getUserId());
//                        x=x+count;
//                    }
//                    System.out.println(x);
//                    if (x==8){
//                        userCouponDao.insertUserReward(userReward);
//                        map.put("returnState",1);
//                    }else{
//                        map.put("returnState",2);
//                    }
//                }
//            }
//        };
//        channel.basicConsume(queueName, true, consumer);//队列会自动删除*/
//    }
//


}
