package com.wufanbao.api.clientservice.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.wufanbao.api.clientservice.common.*;
import com.wufanbao.api.clientservice.common.alipay.AliPay;
import com.wufanbao.api.clientservice.common.wechat.*;
import com.wufanbao.api.clientservice.common.rabbitMQ.RabbitMQSender;
import com.wufanbao.api.clientservice.config.ClientSetting;
import com.wufanbao.api.clientservice.dao.*;
import com.wufanbao.api.clientservice.entity.*;
import com.xiaoleilu.hutool.http.HttpUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Service
public class UserOrderService {
    @Autowired
    private UserOrderDao userOrderDao;
    @Autowired
    private ProductDao productDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private RedisUtils redisUtils;
    @Autowired
    private RabbitMQSender rabbitMQSender;
    @Autowired
    private MachineDao machineDao;
    @Autowired
    private CommonFun commonFun;
    @Autowired
    private ClientSetting clientSetting;
    @Autowired
    private WechatAuth wechatAuth;
    @Autowired
    private AliPay aliPay;
    @Autowired
    private ProductoffDao productoffDao;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    //锁库库存
    private void lockProduct(long userOrderId, List<UserOrderLine> userOrderLines, long machineId) throws ApiServiceException {
        Collections.sort(userOrderLines, Comparator.comparing(UserOrderLine::getProductGlobalId));
        int mainCount = 0;
        for (UserOrderLine userOrderLine : userOrderLines) {
            int quantity = userOrderLine.getQuantity();
            long productGlobalId = userOrderLine.getProductGlobalId();
            boolean isAtt = productGlobalId >= 28 && productGlobalId < 32;//是否是主食
            if (!isAtt) {
                mainCount = mainCount + quantity;
            }
            if (productDao.lockProduct(productGlobalId, machineId, quantity) <= 0) {
                Product product = productDao.getProduct(productGlobalId, machineId);
                throw new ApiServiceException("客官，你购买的" + product.getProductName() + "饭仅剩余" + product.getUseableQuantity() + "份，请调整购物车购买数量");
            }
        }
        if (productDao.lockMachineProduct(machineId, mainCount) <= 0) {
            throw new ApiServiceException("机器商品库存不足，下单失败");
        }
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("orderId", userOrderId);
            jsonObject.put("machineId", machineId);
            rabbitMQSender.unLockSend(jsonObject);
        } catch (TimeoutException e) {
            throw new ApiServiceException("锁库存失败，请重试");
        } catch (IOException e) {
            throw new ApiServiceException("锁库存失败，请重试");
        }
    }

    //解锁库存
    private void unLockProduct(long userOrderId, long machineId) throws ApiServiceException {
        List<UserOrderLine> userOrderLines = userOrderDao.getUserOrderLines(userOrderId);
        Collections.sort(userOrderLines, Comparator.comparing(UserOrderLine::getProductGlobalId));
        int mainCount = 0;
        for (UserOrderLine userOrderLine : userOrderLines) {
            int quantity = userOrderLine.getQuantity();
            int actualQuantity = userOrderLine.getActualQuantity();
            int refund = quantity - actualQuantity;
            long productGlobalId = userOrderLine.getProductGlobalId();
            boolean isAttr = productGlobalId >= 28 && productGlobalId < 32;//是否是主食
            if (!isAttr) {
                mainCount = mainCount + refund;
            }
            if (productDao.unLockProduct(machineId, productGlobalId, refund) <= 0) {
                logger.error("更新商品库存失败!" + userOrderId + "," + productGlobalId);
                throw new ApiServiceException("更新商品库存失败");
            }
        }
        if (productDao.unLockMachineProduct(machineId, mainCount) <= 0) {
            logger.error("更新商品库存失败!" + userOrderId + "," + machineId);
            throw new ApiServiceException("更新机器库存失败" + userOrderId + "," + machineId);
        }
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("userOrderId", userOrderId);
            jsonObject.put("machineId", machineId);
            rabbitMQSender.unLockSend(jsonObject);
        } catch (TimeoutException e) {
            throw new ApiServiceException("解锁库存失败，请重试");
        } catch (IOException e) {
            throw new ApiServiceException("解锁库存失败，请重试");
        }
    }

    //获取用户可以优惠券
    private List<Data> getUseableUserCoupons(long userId, List<Long> productIds, BigDecimal amount, String areaName) {
        List<Data> userCoupons = userDao.getUseableUserCoupons(userId);
        long areaId = userDao.getAreaId(areaName);
        List<Data> results = new ArrayList<>();
        for (Data d : userCoupons) {
            //获取使用规则并转化成实体类
            UseRule useRule = JsonUtils.GsonToBean(d.get("useRule").toString(), UseRule.class);
            ValidityRule validityRule = JsonUtils.GsonToBean(d.get("validityRule").toString(), ValidityRule.class);
            Object objStartTime = d.get("startTime");
            Object objEndTime = d.get("endTime");
            BigDecimal qualifiedMoney = useRule.getQualifiedMoney();//满多少进而
            long productGlobalId = useRule.getProductGlobalId();
            //区域限制
            if (useRule.getAreaId() > 0 && areaId != useRule.getAreaId()) {
                continue;
            }
            //订单金额限制
            if (qualifiedMoney.compareTo(BigDecimal.ZERO) == 1 && amount.compareTo(qualifiedMoney) == -1) {
                continue;
            }
            //商品订单里面含不包含必买商品
            if (!productIds.contains(productGlobalId) && productGlobalId > 0) {
                continue;
            }
            //优惠券有效期
            if (objStartTime != null && objEndTime != null) {
                try {
                    Date startTime = DateUtils.StringToDate(objStartTime.toString());
                    Date endTime = DateUtils.StringToDate(objEndTime.toString());
                    if (!DateUtils.belongCalendar(new Date(), startTime, endTime)) {
                        continue;
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
            results.add(d);
        }
        return results;
    }

    /**
     * 结算时：获取用优惠券、亲密付、企业付、取餐时间等数据
     *
     * @param userId
     * @param amount
     * @param productIds
     * @param areaName
     * @return
     * @throws ApiServiceException
     */
    public Data getUserPayInfo(long userId, BigDecimal amount, List<Long> productIds, String areaName) throws ApiServiceException {
        Data data = new Data();
        if (userId <= 0) {
            throw new ApiServiceException("用户id不能为空");
        }
        User user = userDao.getUser(userId);
        data.put("isPayPaypassword", !StringUtils.isNullOrEmpty(user.getPayPassword()));//是否设置过支付密码
        data.put("usableBalance", user.getUsableBalance());//可用余额
        if (user.getBreakfast() != null && user.getLunch() != null && user.getDinner() != null) {
            data.put("isCommonTime", true);//是否设置了常用取餐时间
            data.put("breakfast", DateUtils.DateToString(user.getBreakfast(), "HH:mm"));
            data.put("lunch", DateUtils.DateToString(user.getLunch(), "HH:mm"));
            data.put("dinner", DateUtils.DateToString(user.getDinner(), "HH:mm"));
        } else {
            data.put("isCommonTime", false);
        }
        data.put("currentTime", DateUtils.DateToString(new Date(), "yyyy-MM-dd"));//当天时间
        data.put("takeTimes", commonFun.getTakeTimes());//取餐时间点列表

        //企业付
        data.put("isCompanyPay", user.getCompanyPayment());
        if (user.getCompanyPayment()) {
            data.put("companyPay", userDao.getCompanyPay(userId));
        }
        //亲密付
        Data familyPay = userDao.getFamilyPay(userId);
        if (familyPay != null) {
            familyPay.put("usableBalance", amount);
        }
        data.put("isFamilyPay", familyPay != null);
        data.put("familyPay", familyPay);//传递主账号余额值。。。。。。。。
        //优惠券
        List<Data> results = getUseableUserCoupons(userId, productIds, amount, areaName);
        data.put("userCouponSize", results.size());
        return data;
    }

    /**
     * 确认订单
     *
     * @return
     */
    @Transactional(rollbackFor = ApiServiceException.class)
    public UserOrder confirmUserOrder(UserOrder userOrder, long userId, String areaName, List<UserOrderLine> userOrderLines, Machine machine) throws ApiServiceException {
        BigDecimal amount = userOrder.getAmount();//订单金额
        BigDecimal actualAmount = userOrder.getActualAmount();//订单实际支付金额
        BigDecimal companyPayAmount = userOrder.getCompanyPayAmount();//企业付金额
        BigDecimal familyPayAmount = userOrder.getFamilyPayAmount();//亲密付金额
        BigDecimal discountAmount = userOrder.getDiscountAmount();//优惠券金额
        long discountId = userOrder.getDiscountId();//优惠券Id
        int payType = userOrder.getPayType();//支付方式
        Date takeTime = userOrder.getTakeTime();
        if (takeTime == null) {
            User user = userDao.getUser(userId);
            if (takeTime == null && user.getBreakfast() != null && user.getBreakfast().compareTo(new Date()) == 1) {
                takeTime = user.getBreakfast();
            }
            if (takeTime == null && user.getLunch() != null && user.getLunch().compareTo(new Date()) == 1) {
                takeTime = user.getLunch();
            }
            if (takeTime == null && user.getDinner() != null && user.getDinner().compareTo(new Date()) == 1) {
                takeTime = user.getDinner();
            }
        }
        if (takeTime == null) {
            takeTime = new Date();
        }
        long machineId = machine.getMachineId();//机器Id
        if (userId <= 0) {
            throw new ApiServiceException("用户id不能为空");
        }
        if (machineId <= 0) {
            throw new ApiServiceException("机器id不能为空");
        }
        if (StringUtils.isNullOrEmpty(areaName)) {
            throw new ApiServiceException("当前下单所在城市不能为空");
        }
        Collections.sort(userOrderLines, Comparator.comparing(UserOrderLine::getProductGlobalId));
        String userOrderDescription = "";
        String imageUrl = "";
        BigDecimal totalAmount = new BigDecimal(0);
        int mainCount = 0;
        List<Long> productIds = new ArrayList<>();
        for (UserOrderLine userOrderLine : userOrderLines) {
            long productGlobalId = userOrderLine.getProductGlobalId();
            productIds.add(productGlobalId);
            Product product = productDao.getMachineProductByProductId(machineId, userOrderLine.getProductGlobalId());//考虑事务，是不是应该na
            BigDecimal onlinePrice = BigDecimal.valueOf(Double.valueOf(product.getOnlinePrice()) / 100);
            if (userOrderLine.getPrice().compareTo(onlinePrice) != 0) {
                throw new ApiServiceException("价格已变更，请重新下单");
            }
            BigDecimal quantity = BigDecimal.valueOf(userOrderLine.getQuantity());
            totalAmount = totalAmount.add(onlinePrice.multiply(quantity));
            if (StringUtils.isNullOrEmpty(userOrderDescription)) {
                userOrderDescription = product.getProductName();
            }
            if (StringUtils.isNullOrEmpty(imageUrl) && !StringUtils.isNullOrEmpty(product.getAttachImageUrls())) {
                String[] str = product.getAttachImageUrls().split(";");
                imageUrl = str[0];
            }
            boolean isAtt = productGlobalId >= 28 && productGlobalId < 32;//是否是主食
            if (!isAtt) {
                mainCount = mainCount + userOrderLine.getQuantity();
            }
        }
        userOrderDescription += "等" + mainCount + "份商品";
        if (totalAmount.compareTo(amount) != 0) {
            throw new ApiServiceException("商品价格计算错误");
        }
        //企业付
        boolean isCompanyPay = companyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        //亲密付
        boolean isFamilyPay = familyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        //优惠券
        boolean isDiscount = discountAmount.compareTo(BigDecimal.ZERO) == 1 && discountId > 1;
        if (isCompanyPay && isFamilyPay) {
            throw new ApiServiceException("企业付与亲密付不能同时使用");
        }
        BigDecimal mathAmount = amount;
        //验证亲密付
        if (isFamilyPay) {
            Data familyPay = userDao.getFamilyPayTest(userId);
            BigDecimal usableBalance = new BigDecimal(familyPay.get("usableBalance").toString());//主账号余额
            int quotaType = Integer.parseInt(familyPay.get("quotaType").toString());//额度类型:0=不限制，1=每日额度，2=没月额度
            boolean limitQuota = Boolean.parseBoolean(familyPay.get("limitQuota").toString());//是否限制额度
            BigDecimal totalQuota = new BigDecimal(familyPay.get("totalQuota").toString());//总额度
            BigDecimal consumeQuota = new BigDecimal(familyPay.get("consumeQuota").toString());//已消耗额度
            boolean disabled = Boolean.parseBoolean(familyPay.get("disabled").toString());//是否启用
            //验证
            mathAmount = mathAmount.subtract(familyPayAmount);
        }
        //验证企业付
        if (isCompanyPay) {
            Data companyPay = userDao.getCompanyPay(userId);
            int quotaType = Integer.parseInt(companyPay.get("quotaType").toString());//额度类型
            boolean limitQuota = Boolean.parseBoolean(companyPay.get("limitQuota").toString());//是否限制额度
            BigDecimal totalQuota = new BigDecimal(companyPay.get("totalQuota").toString());//总额度
            BigDecimal consumeQuota = new BigDecimal(companyPay.get("consumeQuota").toString());//已消耗额度
            //验证
            mathAmount = mathAmount.subtract(companyPayAmount);
        }
        //验证优惠券
        if (isDiscount) {
            List<Data> results = getUseableUserCoupons(userId, productIds, amount, areaName);//获取用户可用优惠券
            HashMap<Long, Data> maps = new HashMap<>();
            for (Data data : results) {
                long couponId = Long.parseLong(data.get("couponId").toString());//优惠券Id
                maps.put(couponId, data);
            }
            if (!maps.containsKey(discountId)) {
                throw new ApiServiceException(1004, "优惠券已过期");
            }
            Data data = maps.get(discountId);
            BigDecimal couponAmount = new BigDecimal(data.get("amount").toString());//优惠券的面值
            if (discountAmount.compareTo(couponAmount) != 0) {
                throw new ApiServiceException("优惠券金额错误");
            }
            mathAmount = mathAmount.subtract(discountAmount);
        }
        if (mathAmount.compareTo(BigDecimal.ZERO) <= 0) {
            mathAmount = BigDecimal.ZERO;
            if (payType == 2 || payType == 3) {//微信或支付宝付款不能为0
                mathAmount = BigDecimal.valueOf(0.01);
            }
        } else {
            //验证金额
            if (mathAmount.compareTo(actualAmount) != 0) {
                throw new ApiServiceException("订单金额或者支付方式选择错误");
            }
        }
        long userOrderId = IDGenerator.generateById("userOrderId", userOrder.getUserId());//生成订单Id
        lockProduct(userOrderId, userOrderLines, machineId);//锁库存
        userOrder.setUserOrderId(userOrderId);
        userOrder.setStatus(2);//已创建
        userOrder.setCreateTime(new Date());
        userOrder.setTakeTime(takeTime);
        userOrder.setJoinCompanyId(machine.getJoinCompanyId());
        userOrder.setMachineId(machineId);
        userOrder.setUserId(userId);
        userOrder.setImageUrl(imageUrl);
        userOrder.setDescription(userOrderDescription);
        if (userOrderDao.insertUserOrder(userOrder) <= 0) {
            throw new ApiServiceException("确认订单失败，请重试");
        }
        for (UserOrderLine userOrderLine : userOrderLines) {
            userOrderLine.setUserOrderId(userOrderId);
            if (userOrderDao.insertUserOrderLine(userOrderLine) <= 0) {
                throw new ApiServiceException("添加订单商品数据失败");
            }
        }
        if (isCompanyPay) {
            companyPay(userId, userOrderId, userOrder.getCompanyPayAmount());
        }
        if (isFamilyPay) {
            familyPay(userId, userOrderId, userOrder.getFamilyPayAmount());
        }
        if (isDiscount) {
            couponPay(userOrderId, userOrder.getDiscountId(), userOrder.getDiscountAmount());
        }
        if (userOrder.getUserOrderId() > 0) {
            redisUtils.set("str" + userOrder.getUserOrderId(), String.valueOf(userOrder.getUserOrderId()));
            redisUtils.expire("str" + userOrder.getUserOrderId(), 175);
        }
        return userOrder;
    }

    //取消订单
    @Transactional(rollbackFor = ApiServiceException.class)
    public void cancleUserOrder(long userOrderId) throws ApiServiceException {
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        long userId = userOrder.getUserId();
        BigDecimal companyPayAmount = userOrder.getCompanyPayAmount();//企业付金额
        BigDecimal familyPayAmount = userOrder.getFamilyPayAmount();//亲密付金额
        BigDecimal discountAmount = userOrder.getDiscountAmount();//优惠券金额
        long discountId = userOrder.getDiscountId();//优惠券Id
        userOrder = payQuery(userOrderId, userOrder.getPayType());
        if (userOrder.getStatus() != 2) {//待支付的订单才能取消订单
            if (userOrder.getStatus() >= 3) {
                throw new ApiServiceException("订单已支付，无法取消");
            }
            if (userOrder.getStatus() <= 0) {
                throw new ApiServiceException("订单已取消，无法取消");
            }
            return;
        }
        //企业付款
        boolean isCompanyPay = userOrder.getCompanyPayAmount().compareTo(BigDecimal.ZERO) == 1;
        //亲密付
        boolean isFamilyPay = userOrder.getFamilyPayAmount().compareTo(BigDecimal.ZERO) == 1;
        //优惠券金额
        boolean isDiscount = userOrder.getDiscountAmount().compareTo(BigDecimal.ZERO) == 1 && userOrder.getDiscountId() > 0;
        if (isCompanyPay) {
            companyRefund(userOrderId, userId, companyPayAmount);
        }
        if (isFamilyPay) {
            familyRefund(userOrderId, userOrder.getCreateTime(), userId, familyPayAmount);
        }
        if (isDiscount) {
            couponRefund(discountId, userOrderId);
        }
        unLockProduct(userOrderId, userOrder.getMachineId());//解锁
        userOrderDao.cancelUserOrder(userOrderId);//取消订单
    }

    //失效订单
    @Transactional(rollbackFor = ApiServiceException.class)
    public List<UserOrder> getInvalidUserOrder(Long userOrderId) throws ApiServiceException {
        return userOrderDao.getInvalidUserOrder(userOrderId);
    }

    //超过时间没有取走的订单，自动退款
    @Transactional(rollbackFor = ApiServiceException.class)
    public List<UserOrder> getMakingUserOrder(Long userOrderId) throws ApiServiceException {
        return userOrderDao.getMakingUserOrder(userOrderId);
    }

    //支付订单
    @Transactional(rollbackFor = ApiServiceException.class)
    public Object appPayUserOrder(long userId, long userOrderId, int payType, String payPassword, BigDecimal amount, String ip) throws ApiServiceException {
        if (userOrderId < 0) {
            throw new ApiServiceException("订单Id错误");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        userOrder = payQuery(userOrderId, userOrder.getPayType());
        if (userOrder.getStatus() != 2) {
            throw new ApiServiceException("订单不在待支付状态，无法进行支付");
        }
        if (amount.compareTo(BigDecimal.ZERO) == 1) {
            switch (payType) {
                case 2://微信
                    try {
                        if (StringUtils.isNullOrEmpty(ip)) {
                            throw new ApiServiceException("客户端Ip地址不能为空");
                        }
                        int originateId = 2;
                        if (userOrderDao.updateOriginateId(originateId, userOrderId) <= 0) {
                            throw new ApiServiceException("订单付款失败，请重试");
                        }
                        WechatPay wechatPay = new WechatPay();
                        Map<String, String> map = wechatPay.payOrder(String.valueOf(userOrderId), "APP", "", amount, "伍饭宝-下单点餐支付", ip, clientSetting.getPayCallback() + "/webapi/wechat/payNotify");
                        if (redisUtils.get("str" + userOrder.getUserOrderId()) == null) {
                            throw new ApiServiceException("订单已过期，请重新下单");
                        }
                        return map;
                    } catch (Exception ex) {
                        throw new ApiServiceException("微信支付失败:" + ex.getMessage());
                    }
                case 3://支付宝
                    if (redisUtils.get("str" + userOrder.getUserOrderId()) == null) {
                        throw new ApiServiceException("订单已过期，请重新下单");
                    }
                    try {
                        int originateId = 3;
                        if (userOrderDao.updateOriginateId(originateId, userOrderId) <= 0) {
                            throw new ApiServiceException("订单付款失败，请重试");
                        }
                        return aliPay.appPayOrder(String.valueOf(userOrderId), amount, "伍饭宝-下单点餐支付", "下单支付", clientSetting.getPayCallback() + "/webapi/alipay/payNotify");
                    } catch (AlipayApiException e) {
                        throw new ApiServiceException("支付宝支付失败:" + e.getErrMsg());
                    }
                case 4://余额
                    checkPayPassword(userId, payPassword);
                    if (redisUtils.get("str" + userOrder.getUserOrderId()) == null) {
                        throw new ApiServiceException("订单已过期，请重新下单");
                    }
                    int originateId = 1;
                    if (userOrderDao.updateOriginateId(originateId, userOrderId) <= 0) {
                        throw new ApiServiceException("订单付款失败，请重试");
                    }
                    balancePay(userId, userOrder, amount);
                    return "success";
                default:
                    throw new ApiServiceException("支付方式选择错误");
            }
        } else {
            checkPayPassword(userId, payPassword);
            Date invalidTime = DateUtils.getAfterTime(clientSetting.getUserOrderInvalidTime());
            if (userOrder.getTakeTime() != null) {
                invalidTime = DateUtils.getAfterTime(userOrder.getTakeTime(), clientSetting.getUserOrderInvalidTime());
            }
            if (userOrderDao.payUserOrder(userOrderId, payType, invalidTime) <= 0) {
                throw new ApiServiceException("订单支付失败，请重试");
            }
            return "";
        }
    }

    //微信公众号支付订单
    @Transactional(rollbackFor = ApiServiceException.class)
    public Data wxPayUserOrder(long userId, long userOrderId, int payType, BigDecimal amount, String ip, String openId) throws ApiServiceException {
        if (userOrderId < 0) {
            throw new ApiServiceException("订单Id错误");
        }
        if (StringUtils.isNullOrEmpty(openId)) {
            throw new ApiServiceException("openId参数错误");
        }

        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder.getStatus() != 2) {
            throw new ApiServiceException("订单不在待支付状态，无法进行支付");
        }
        //优惠券金额
        boolean isDiscount = userOrder.getDiscountAmount().compareTo(BigDecimal.ZERO) == 1 && userOrder.getDiscountId() > 0;
        /*if (isDiscount) {
            couponPay(userOrderId, userOrder.getDiscountId(), userOrder.getDiscountAmount());
        }*/
        if (userOrder.getActualAmount().compareTo(amount) != 0) {
            throw new ApiServiceException("付款金额错误");
        }
        if (payType != 2) {
            throw new ApiServiceException("支付方式选择错误");
        }
        try {
            if (StringUtils.isNullOrEmpty(ip)) {
                throw new ApiServiceException("客户端Ip地址不能为空");
            }
            int originateId = 4;
            if (userOrderDao.updateOriginateId(originateId, userOrderId) <= 0) {
                throw new ApiServiceException("订单付款失败，请重试");
            }
            Data data = wechatAuth.getPay(String.valueOf(userOrderId), openId, amount, "伍饭宝-下单点餐支付", ip, clientSetting.getPayCallback() + "/webapi/wechat/payNotify");
            return data;
        } catch (Exception ex) {
            throw new ApiServiceException("微信支付失败");
        }
    }

    //支付宝h5 支付
    @Transactional(rollbackFor = ApiServiceException.class)
    public String aliWapPayUserOrder(long userOrderId, int payType, BigDecimal amount) throws ApiServiceException {
        if (userOrderId < 0) {
            throw new ApiServiceException("订单Id错误");
        }

        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder.getStatus() != 2) {
            throw new ApiServiceException("订单不在待支付状态，无法进行支付");
        }
        //优惠券金额
        boolean isDiscount = userOrder.getDiscountAmount().compareTo(BigDecimal.ZERO) == 1 && userOrder.getDiscountId() > 0;
        /*if (isDiscount) {
            couponPay(userOrderId, userOrder.getDiscountId(), userOrder.getDiscountAmount());
        }*/
        if (userOrder.getActualAmount().compareTo(amount) != 0) {
            throw new ApiServiceException("付款金额错误");
        }
        if (payType != 3) {
            throw new ApiServiceException("支付方式选择错误");
        }
        int originateId = 5;
        if (userOrderDao.updateOriginateId(originateId, userOrderId) <= 0) {
            throw new ApiServiceException("订单付款失败，请重试");
        }
        try {
            String form = aliPay.wapPayOrder(String.valueOf(userOrderId), amount, "伍饭宝-下单点餐支付", "下单支付", clientSetting.getPayCallback() + "/webapi/alipay/payNotify", "");
            return form;
        } catch (Exception ex) {
            throw new ApiServiceException("微信支付失败");
        }
    }

    //查询订单的支付状态
    @Transactional(rollbackFor = ApiServiceException.class)
    public UserOrder payQuery(long userOrderId, int payType) throws ApiServiceException {
        if (userOrderId < 0) {
            throw new ApiServiceException("订单Id错误");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder.getStatus() == 3) {
            return userOrder;
        }
        if (userOrder.getStatus() == 0) {
            throw new ApiServiceException("订单已取消，请重新下单");
        }
        switch (payType) {
            case 2:
                try {
                    WechatPay wechatPay = new WechatPay();
                    Map<String, String> map = wechatPay.queryOrder(String.valueOf(userOrderId));//查询支付订单
                    String returnCode = map.get("return_code");
                    if (returnCode.equals("FAIL")) {
                        throw new ApiServiceException(map.get("return_msg"));
                    }
                    String resultCode = map.get("result_code");
                    if (resultCode.equals("SUCCESS")) {
                        long totalFee = Long.parseLong(map.get("total_fee"));
                        String transaction_id = map.get("transaction_id");//微信支付订单号
                        BigDecimal amount = BigDecimal.valueOf(totalFee).divide(BigDecimal.valueOf(100));
                        userOrder = payedUserOrder(userOrderId, amount, transaction_id, payType);//status=0或者！=2,订单失效，返回order;否则微信支付成功，status=3
                    }
                    return userOrder;
                } catch (Exception ex) {
                    throw new ApiServiceException("查询失败" + ex.getMessage());
                }
            case 3:
                try {
                    AliPay aliPay = new AliPay();
                    AlipayTradeQueryResponse response = aliPay.queryOrder(String.valueOf(userOrderId));
                    if (response.isSuccess()) {
                        Double totalFee = Double.parseDouble(response.getTotalAmount());
                        String trade_no = response.getTradeNo();//支付宝订单号
                        userOrder = payedUserOrder(userOrderId, BigDecimal.valueOf(totalFee), trade_no, payType);
                    }
                    return userOrder;
                } catch (Exception ex) {
                    throw new ApiServiceException("查询失败" + ex.getMessage());
                }
            default:
                return userOrder;
            //throw new ApiServiceException("支付方式选择错误");
        }
    }

    //查询订单的退款状态
    @Transactional(rollbackFor = ApiServiceException.class)
    public UserOrder refundQuery(long userOrderId, int payType) throws ApiServiceException {
        if (userOrderId < 0) {
            throw new ApiServiceException("订单Id错误");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder.getStatus() != -1) {//不在退款中
            return userOrder;
        }
        if (userOrder.getPayTime() == null) {
            if (userOrderDao.updateStatus(userOrderId) <= 0) {
                throw new ApiServiceException("订单退款失败");
            }
            return userOrder;
        }
        switch (payType) {
            case 2:
                try {
                    WechatPay wechatPay = new WechatPay();
                    Map<String, String> map = wechatPay.refundQuery(String.valueOf(userOrderId));
                    String returnCode = map.get("return_code");
                    if (returnCode.equals("FAIL")) {
                        throw new ApiServiceException(map.get("return_msg"));
                    }
                    String resultCode = map.get("result_code");
                    if (resultCode.equals("SUCCESS")) {
                        long totalFee = Long.parseLong(map.get("total_fee"));
                        long refundFee=Long.parseLong(map.get("refund_fee"));
                        String transaction_id = map.get("transaction_id");//微信支付订单号
                        long out_refund_no = Long.parseLong(map.get("out_refund_no_0"));//商户退款单号
                        BigDecimal amount = BigDecimal.valueOf(refundFee).divide(BigDecimal.valueOf(100));
                        userOrder = refundedUserOrder(userOrderId, out_refund_no, amount, transaction_id, payType);
                    }
                    return userOrder;
                } catch (Exception ex) {
                    throw new ApiServiceException("查询失败" + ex.getMessage());
                }
            case 3:
                try {
                    AliPay aliPay = new AliPay();
                    AlipayTradeFastpayRefundQueryResponse response = null;
                    response = aliPay.refundQuery(String.valueOf(userOrderId));
                    if (response.isSuccess()) {
                        Double totalFee = Double.parseDouble(response.getTotalAmount());
                        Double refundAmount = Double.parseDouble(response.getRefundAmount());
                        String trade_no = response.getTradeNo();//支付宝订单号
                        long capitalLogId = IDGenerator.generateById("capitalLogId", userOrderId);
                        userOrder = refundedUserOrder(userOrderId, capitalLogId, BigDecimal.valueOf(refundAmount), trade_no, payType);
                    }
                    return userOrder;
                } catch (Exception ex) {
                    throw new ApiServiceException("查询失败" + ex.getMessage());
                }
            default:
                throw new ApiServiceException("支付方式选择错误");

        }
    }

    /**
     * 验证支付密码
     *
     * @param userId
     * @param payPassword
     * @throws ApiServiceException
     */
    private void checkPayPassword(long userId, String payPassword) throws ApiServiceException {
        if (StringUtils.isNullOrEmpty(payPassword)) {
            throw new ApiServiceException("支付密码不能为空");
        }
        String key = Long.toString(userId, 16) + 4;
        long count = redisUtils.incr(key);
        if (count > 2) {
            redisUtils.expire(key, 60 * 3);
            throw new ApiServiceException(1005, "支付密码错误已达上限，请3分钟后重试或者修改支付密码");
        }
        // 验证支付密码
        User user = userDao.getUser(userId);
        if (StringUtils.isNullOrEmpty(user.getPayPassword())) {
            throw new ApiServiceException(1002, "请先设置支付密码");
        }
        String md5Password = DigestUtils.md5Hex(payPassword);

        if (!user.getPayPassword().equals(md5Password)) {
            throw new ApiServiceException(1003, "支付密码错误,还有" + (3 - count) + "次机会");
        }
        redisUtils.del(key);
    }

    /***
     * 亲密付
     * @param userId 用户id
     * @param amount 亲密付额度
     */
    private void familyPay(long userId, long userOrderId, BigDecimal amount) throws ApiServiceException {
        Data familyPay = userDao.getFamilyPayTest(userId);
        BigDecimal usableBalance = new BigDecimal(familyPay.get("usableBalance").toString());//主账号余额
        long masterUserId = Long.parseLong(familyPay.get("masterUserId").toString());//主帐户id
        int quotaType = Integer.parseInt(familyPay.get("quotaType").toString());//额度类型:0=不限制，1=每日额度，2=没月额度
        boolean limitQuota = Boolean.parseBoolean(familyPay.get("limitQuota").toString());//是否限制额度
        BigDecimal totalQuota = new BigDecimal(familyPay.get("totalQuota").toString());//总额度
        BigDecimal consumeQuota = new BigDecimal(familyPay.get("consumeQuota").toString());//已消耗额度
        boolean disabled = Boolean.parseBoolean(familyPay.get("disabled").toString());//是否启用
        if (!disabled) {
            throw new ApiServiceException("亲密付账号被禁用");
        }
        if (usableBalance.compareTo(BigDecimal.ZERO) != 1 || usableBalance.compareTo(amount) != 1) {
            throw new ApiServiceException("伍饭宝：亲密付主账号余额不足");
        }
        //限额
        if (limitQuota && quotaType > 0) {
            BigDecimal useableQuota = totalQuota.subtract(consumeQuota);
            if (useableQuota.compareTo(amount) != 1) {
                throw new ApiServiceException("亲密付，已超过限额");
            }
        }
        try {
            //亲密付，更改限额表
            if (userDao.familyPay(userId, amount) <= 0) {
                throw new ApiServiceException("变更限额数据失败");
            }
            /*if (amount.compareTo(BigDecimal.ZERO)==0) {
                userDao.familyPay(userId, amount);
                throw new ApiServiceException("变更限额数据失败");
            }*/
            //亲密付，更改扣除主帐户资金
            if (userDao.masterPay(masterUserId, amount) <= 0) {
                throw new ApiServiceException("变更主帐户数据失败");
            }
            long capitalLogId = IDGenerator.generateById("userCapitalLogId", masterUserId);
            if (userDao.insertUserCapital(capitalLogId, masterUserId, amount.negate(), "familyPay", userOrderId, "亲密付") <= 0) {
                throw new ApiServiceException("插入资金流水失败");
            }
            if (userOrderDao.insertUserOrderCapital(userOrderId, 5, "familyPay", String.valueOf(userId), amount, "亲密付支付") <= 0) {
                throw new ApiServiceException("添加订单资金记录失败");
            }
        } catch (Exception ex) {
            throw new ApiServiceException("亲密付，支付失败:" + ex.getMessage());
        }
    }

    private void familyRefund(long userOrderId, Date createTime, long userId, BigDecimal amount) throws ApiServiceException {
        try {
            //Data familyPay = userDao.getFamilyPay(userId);
            //BigDecimal usableBalance = new BigDecimal(familyPay.get("usableBalance").toString());//主账号余额
            //long masterUserId = Long.parseLong(familyPay.get("masterUserId").toString());//主帐户id
            UserCapitalLog userCapitalLog = userDao.getUserCapitallogBysourceType(userOrderId, "familyPay");
            long masterUserId = userCapitalLog.getUserId();
            //亲密付，退还限额
            Data data = userDao.getFamilyPay(userId);
            if (data != null) {
                Date updateTime = DateUtils.StringToDate(data.get("updateTime").toString());//亲密付限额变更时间
                if (createTime.compareTo(updateTime) == 1) {
                    if (userDao.refundFamilyPay(userId, amount) <= 0) {
                        throw new ApiServiceException("变更限额数据失败");
                    }
                }
            }
            //亲密付，退还主帐户资金
            if (userDao.refundMasterPay(masterUserId, amount) <= 0) {
                throw new ApiServiceException("变更主帐户数据失败");
            }
            long capitalLogId = IDGenerator.generateById("userCapitalLogId", masterUserId);
            if (userDao.insertUserCapital(capitalLogId, masterUserId, amount, "familyRefund", userOrderId, "亲密付退款") <= 0) {
                throw new ApiServiceException("插入资金流水失败");
            }
            /*update userordercapital set refund=1,refundAmount=#{refundAmount},refundTime=CURRENT_TIMESTAMP where
            userOrderId=#{userOrderId} and capitalType=#{capitalType}*/
            if (userOrderDao.refundUserOrderCapital(userOrderId, 5, amount) <= 0) {
                throw new ApiServiceException("退款修改订单资金记录失败");
            }
        } catch (Exception ex) {
            throw new ApiServiceException("亲密付，退款失败:" + ex.getMessage());
        }
    }

    /**
     * 企业付
     *
     * @param userId 用户id
     * @param amount 企业付额度
     */
    private void companyPay(long userId, long userOrderId, BigDecimal amount) throws ApiServiceException {
        Data companyPay = userDao.getCompanyPay(userId);
        long companyId = Long.parseLong(companyPay.get("companyId").toString());//公司id
        BigDecimal balance = new BigDecimal(companyPay.get("balance").toString());//企业余额
        int quotaType = Integer.parseInt(companyPay.get("quotaType").toString());//额度类型
        boolean limitQuota = Boolean.parseBoolean(companyPay.get("limitQuota").toString());//是否限制额度
        BigDecimal totalQuota = new BigDecimal(companyPay.get("totalQuota").toString());//总额度
        BigDecimal consumeQuota = new BigDecimal(companyPay.get("consumeQuota").toString());//已消耗额度

        if (balance.compareTo(BigDecimal.ZERO) != 1 || balance.compareTo(amount) != 1) {
            throw new ApiServiceException("企业付，主账号余额不足");
        }
        //限额
        if (limitQuota && quotaType > 0) {
            BigDecimal useableQuota = totalQuota.subtract(consumeQuota);
            if (useableQuota.compareTo(amount) != 1) {
                throw new ApiServiceException("企业付，已超过限额");
            }
        }
        try {
            //企业付，更改限额表
            if (userDao.updateUserQuota(userId, amount) <= 0) {
                throw new ApiServiceException("变更限额数据失败");
            }
            //企业付，更改扣除公司帐户资金
            if (userDao.updateCompanyBalance(companyId, amount) <= 0) {
                throw new ApiServiceException("变更主帐户数据失败");
            }

            long capitalLogId = IDGenerator.generateById("capitalLogId", userId);
            if (userDao.insertCompanyCapitalLog(capitalLogId, companyId, 0, amount, 0, "companyPay", userOrderId, 3, "企业付") <= 0) {
                throw new ApiServiceException("插入资金流水失败");
            }
            if (userOrderDao.insertUserOrderCapital(userOrderId, 7, "companyPay", String.valueOf(companyId), amount, "企业付支付") <= 0) {
                throw new ApiServiceException("添加订单资金记录失败");
            }
        } catch (Exception ex) {
            throw new ApiServiceException("企业付，支付失败:" + ex.getMessage());
        }
    }

    private void companyRefund(long userOrderId, long userId, BigDecimal amount) throws ApiServiceException {
        try {
            Data companyPay = userDao.getCompanyPay(userId);
            long companyId = Long.parseLong(companyPay.get("companyId").toString());//公司id

            //企业付，更改限额表
            if (userDao.refundUserQuota(userId, amount) <= 0) {
                throw new ApiServiceException("变更限额数据失败");
            }
            //企业付，退还公司帐户资金
            if (userDao.refundCompanyBalance(companyId, amount) <= 0) {
                throw new ApiServiceException("变更主帐户数据失败");
            }
            long capitalLogId = IDGenerator.generateById("capitalLogId", userId);
            if (userDao.insertCompanyCapitalLog(capitalLogId, companyId, 0, amount, 1, "companyRefund", userOrderId, 3, "企业付退款") <= 0) {
                throw new ApiServiceException("插入资金流水失败");
            }
            if (userOrderDao.refundUserOrderCapital(userOrderId, 7, amount) <= 0) {
                throw new ApiServiceException("退款修改订单资金记录失败");
            }
        } catch (Exception ex) {
            throw new ApiServiceException("企业付，退款失败:" + ex.getMessage());
        }
    }

    //优惠券付款
    private void couponPay(long userOrderId, long discountId, BigDecimal amount) throws ApiServiceException {
        Data userCoupon = userDao.getUserCoupon(discountId);//获取用户可用优惠券
        int status = Integer.parseInt(userCoupon.get("status").toString());
        if (status != 1) {
            throw new ApiServiceException(1004, "订单支付所使用的优惠券已过期");
        }
        BigDecimal couponAmount = new BigDecimal(userCoupon.get("amount").toString());//优惠券的面值
        if (amount.compareTo(couponAmount) != 0) {
            throw new ApiServiceException("优惠券金额错误");
        }
        //使用优惠券
        if (userDao.useCoupon(discountId) <= 0) {
            throw new ApiServiceException("使用优惠券错误");
        }
        long couponDefinitionId = Long.parseLong(userCoupon.get("couponDefinitionId").toString());
        if (userDao.addCouponDefinitionUsed(couponDefinitionId) <= 0) {
            throw new ApiServiceException("使用优惠券错误");
        }
        if (userOrderDao.insertUserOrderCapital(userOrderId, 9, "couponPay", String.valueOf(discountId), amount, "优惠券支付") <= 0) {
            throw new ApiServiceException("添加订单资金记录失败");
        }
    }

    private void couponRefund(long discountId, long userOrderId) throws ApiServiceException {
        try {
            Data userCoupon = userDao.getUserCoupon(discountId);//获取用户可用优惠券
            int status = Integer.parseInt(userCoupon.get("status").toString());
            BigDecimal couponAmount = new BigDecimal(userCoupon.get("amount").toString());//优惠券的面值

            if (status == 1 || status == 3) {
                throw new ApiServiceException("优惠券已过期获取并未使用，无需退还");
            }
            //退还优惠券
            if (userDao.refundCoupon(discountId) <= 0) {
                throw new ApiServiceException("退还优惠券错误");
            }
            long couponDefinitionId = Long.parseLong(userCoupon.get("couponDefinitionId").toString());
            if (userDao.minusCouponDefinitionUsed(couponDefinitionId) <= 0) {
                throw new ApiServiceException("使用优惠券错误");
            }
            if (userOrderDao.refundUserOrderCapital(userOrderId, 9, couponAmount) <= 0) {
                throw new ApiServiceException("退款修改订单资金记录失败");
            }
        } catch (Exception ex) {
            throw new ApiServiceException("优惠券退款失败:" + ex.getMessage());
        }
    }

    //余额支付
    private void balancePay(long userId, UserOrder userOrder, BigDecimal amount) throws ApiServiceException {
        long userOrderId = userOrder.getUserOrderId();
        User user = userDao.getUser(userId);
        if (user.getUsableBalance().compareTo(amount) != 1) {
            throw new ApiServiceException(1005, "余额不足,请充值");
        }
        if (userDao.useBalance(userId, amount) <= 0) {
            throw new ApiServiceException("余额支付失败，请重试");
        }
        long userCapitalLogId = IDGenerator.generateById("userCapitalLogId", userId);
        if (userDao.insertUserCapital(userCapitalLogId, userId, amount.negate(), "balancePay", userOrderId, "订单付款") <= 0) {
            throw new ApiServiceException("余额支付失败，请重试");
        }
        if (userOrderDao.insertUserOrderCapital(userOrderId, 4, "balancePay", String.valueOf(userId), amount, "余额支付") <= 0) {
            throw new ApiServiceException("添加订单资金记录失败");
        }
        Date invalidTime = DateUtils.getAfterTime(clientSetting.getUserOrderInvalidTime());
        if (userOrder.getTakeTime() != null) {
            invalidTime = DateUtils.getAfterTime(userOrder.getTakeTime(), clientSetting.getUserOrderInvalidTime());
        }
        if (userOrderDao.payUserOrder(userOrderId, 4, invalidTime) <= 0) {
            throw new ApiServiceException("订单支付失败，请重试");
        }
    }

    private void balanceRefund(long userId, long userOrderId, BigDecimal amount) throws ApiServiceException {
        if (userDao.refundBalance(userId, amount) <= 0) {
            throw new ApiServiceException("余额退款失败，请重试");
        }
        long userCapitalLogId = IDGenerator.generateById("userCapitalLogId", userId);
        if (userDao.insertUserCapital(userCapitalLogId, userId, amount, "balanceRefund", userOrderId, "订单退款") <= 0) {
            throw new ApiServiceException("余额支付失败，请重试");
        }
        if (userOrderDao.refundUserOrderCapital(userOrderId, 4, amount) <= 0) {
            throw new ApiServiceException("退款变更订单资金记录失败");
        }
        /*if (userOrderDao.refundedUserOrder(userOrderId) <= 0) {
            throw new ApiServiceException("订单退款失败，请重试");
        }*/
    }

    //生成取餐过程记录表
    private void generateUserOrderProductLines(long userOrderId, long userId) {
        //防止重复取餐产生重复记录
        List<Data> userOrderProductLines = userOrderDao.getUserOrderProductLines(userOrderId);
        if (userOrderProductLines.size() > 0) {
            return;
        }
        List<Data> userOrderLineAndProducts = userOrderDao.getUserOrderLineAndProducts(userOrderId);
        for (Data data : userOrderLineAndProducts) {
            boolean isStaple = Boolean.parseBoolean(data.get("isStaple").toString());
            if (!isStaple) {
                continue;
            }
            int quantity = Integer.parseInt(data.get("quantity").toString());
            long productGlobalId = Long.parseLong(data.get("productGlobalId").toString());
            for (int j = 0; j < quantity; j++) {
                long userOrderProductLineId = IDGenerator.generateById("userOrderProductLineId", userId);
                UserOrderProductLine userOrderProductLine = new UserOrderProductLine();
                userOrderProductLine.setProductGlobalId(productGlobalId);
                userOrderProductLine.setUserOrderId(userOrderId);
                userOrderProductLine.setProductOffId(0);
                userOrderProductLine.setUserOrderProductLineId(userOrderProductLineId);
                userOrderDao.inserUserOrderProductLine(userOrderProductLine);
            }
        }
    }

    //支付宝／微信 支付成功后的通知 2019.3.23 支付失败，退款
    @Transactional(rollbackFor = ApiServiceException.class)
    public void payNotify(long userOrderId, BigDecimal amount, String tradeNo, int payType) throws ApiServiceException {
        UserOrder userOrder = payedUserOrder(userOrderId, amount, tradeNo, payType);
        if (userOrder.getStatus() == 0) {
            refundedUserOrderinvalid(userOrder, userOrderId, amount, tradeNo, payType);
        }
    }

    //status=0,微信支付宝已支付，退款
    public void refundedUserOrderinvalid(UserOrder userOrder, long userOrderId, BigDecimal amount, String tradeNo, int payType) throws ApiServiceException {
        if (amount.compareTo(BigDecimal.ZERO) == 1) {
           /* UserOrder userOrderDB = userOrderDao.getUserOrder(userOrderId);
            if (userOrderDB.getActualAmount().compareTo(amount) != 0) {
                throw new ApiServiceException("订单金额错误");
            }
            Date invalidTime=userOrder.getTakeTime();
            if (userOrderDao.notifyPayUserOrderInvalid(userOrderId, payType, invalidTime, amount) <= 0) { //失效时间，付款时间，数量等
                throw new ApiServiceException("支付订单失败");
            }*/
            switch (payType) {
                case 2://微信退款
                    try {
                        if (userOrderDao.refundUserOrderinvalid(userOrderId, payType) <= 0) {
                            throw new ApiServiceException("订单退款失败，请重试");
                        }
                        WechatPay wechatPay = new WechatPay();
                        long capitalLogId = IDGenerator.generateById("userCapitalLogId", userOrderId);
                        wechatPay.refund(String.valueOf(userOrderId), String.valueOf(capitalLogId), amount, amount, clientSetting.getPayCallback() + "/webapi/wechat/refundNotify");
                    } catch (Exception e) {
                        throw new ApiServiceException("微信退款失败：" + e.getMessage());
                    }
                    break;
                case 3://支付宝退款
                    try {
                        if (userOrderDao.refundUserOrder(userOrderId) <= 0) {
                            throw new ApiServiceException("订单退款失败，请重试");
                        }
                        AliPay aliPay = new AliPay();
                        aliPay.refund(String.valueOf(userOrderId), amount);
                    } catch (AlipayApiException e) {
                        throw new ApiServiceException("支付宝退款失败：" + e.getMessage());
                    }
                    break;
                default:
                    throw new ApiServiceException("退款失败");
            }
        } else {
            if (userOrderDao.refundedUserOrder(userOrderId) <= 0) {
                throw new ApiServiceException("订单退款失败，请重试");
            }
        }
    }

    private UserOrder payedUserOrder(long userOrderId, BigDecimal amount, String tradeNo, int payType) throws ApiServiceException {
        if (userOrderId <= 0) {
            throw new ApiServiceException("订单id不能为空");
        }
        if (amount.compareTo(BigDecimal.ZERO) != 1) {
            throw new ApiServiceException("支付金额错误");
        }
        if (StringUtils.isNullOrEmpty(tradeNo)) {
            throw new ApiServiceException("交易流水号不能为空");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);

        if (userOrder == null) {
            throw new ApiServiceException("支付的订单不存在!"+userOrderId);
        }
        if (userOrder.getStatus() >= 3) {
            return userOrder;
        }

        //如果订单已经失效，直接返回
        if (userOrder.getStatus() != 2) {
            return userOrder;
        }
        if (userOrder.getActualAmount().compareTo(amount) != 0) {
            throw new ApiServiceException("订单金额错误");
        }

        Date invalidTime = DateUtils.getAfterTime(clientSetting.getUserOrderInvalidTime());
        if (userOrder.getTakeTime() != null) {
            invalidTime = DateUtils.getAfterTime(userOrder.getTakeTime(), clientSetting.getUserOrderInvalidTime());
        }

        if (userOrderDao.notifyPayUserOrder(userOrderId, payType, invalidTime, amount) <= 0) { //设置订单状态=3，失效时间，付款时间，数量等
            throw new ApiServiceException("支付订单失败");
        }
        userOrder.setStatus(3);
        long capitalLogId = IDGenerator.generateById("userCapitalLogId", userOrderId);
        String payTypeName = "";
        String payTypeDescription = "";
        if (payType == 2) {
            payTypeName = "wechat";
            payTypeDescription = "微信支付";
        } else {
            payTypeName = "alipay";
            payTypeDescription = "支付宝支付";
        }
        if (userDao.insertUserCapital(capitalLogId, userOrder.getUserId(), amount.negate(), payTypeName, userOrderId, payTypeDescription) <= 0) {
            throw new ApiServiceException("插入资金流水失败");
        }
        if (userOrderDao.insertUserOrderCapital(userOrderId, payType, payTypeName, tradeNo, amount, payTypeDescription) <= 0) {
            throw new ApiServiceException("添加订单资金记录失败");
        }
        return userOrder;
    }

    //失效订单退款和机器故障退款
    @Transactional(rollbackFor = ApiServiceException.class)
    public void refundUserOrder(long userOrderId) throws ApiServiceException {
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder.getStatus() > 4) {
            throw new ApiServiceException("订单已生效，无法退款");
        }
        if (userOrder.getStatus() < 3) {
            throw new ApiServiceException("订单未支付，无法退款");
        }
        List<ProductOff> productOffs = productoffDao.getProductoffBySourceid(userOrderId);
        if (productOffs == null) {
            return;
        }
        List<UserOrderLine> userOrderLines = userOrderDao.getUserOrderLines(userOrderId);
        int total=0;
        if(userOrderLines.size()==1){
            total = userOrderLines.get(0).getQuantity();
        }
        if (productOffs.size() == 0||total==1) {
            if(productOffs.size()==0){
                unLockProduct(userOrderId, userOrder.getMachineId());
            }
        } else {
            int sum = 0;
            //部分出餐库存变更
            for (UserOrderLine userOrderLine : userOrderLines) {
                sum += userOrderLine.getQuantity();
            }
            if (productOffs.size() != sum) {
                int mainCount = 0;
                for (UserOrderLine userOrderLine : userOrderLines) {
                    UserOrderLine ul = new UserOrderLine();
                    ul.setProductGlobalId(userOrderLine.getProductGlobalId());
                    ul.setQuantity(userOrderLine.getQuantity());
                    ul.setActualQuantity(0);
                    for (ProductOff productOff : productOffs) {
                        if (productOff.getProductGlobalId() == userOrderLine.getProductGlobalId()) {
                            ul.setActualQuantity(ul.getActualQuantity() + 1);
                        }
                    }
                    int refund = ul.getQuantity() - ul.getActualQuantity();
                    long productGlobalId = userOrderLine.getProductGlobalId();
                    if (productDao.unLockProduct(userOrder.getMachineId(), productGlobalId, refund) <= 0) {
                        logger.error("更新商品库存失败!" + userOrderId + "," + productGlobalId);
                        throw new ApiServiceException("更新商品库存失败!");
                    }
                    boolean isAttr = productGlobalId >= 28 && productGlobalId < 32;//是否是主食
                    if (!isAttr) {
                        mainCount = mainCount + refund;
                    }
                }
                if (productDao.unLockMachineProduct(userOrder.getMachineId(), mainCount) <= 0) {
                    logger.error("更新商品库存失败!" + userOrderId + "," + userOrder.getMachineId());
                    throw new ApiServiceException("更新机器库存失败");
                }
            }
        }
        int refundQuantity =0;
        BigDecimal refundAmount=BigDecimal.valueOf(0);
        for (UserOrderLine userOrderLine : userOrderLines) {
            refundQuantity=userOrderLine.getQuantity()-userOrderLine.getActualQuantity();
            userOrderLine.setRefundQuantity(refundQuantity);
            refundAmount=userOrderLine.getPrice().multiply(BigDecimal.valueOf(refundQuantity)).add(refundAmount);
        }
        if(refundAmount.compareTo(userOrder.getAmount())==0){
            refund(userOrderId,userOrder);
        }else {
            refundMoney(userOrderId, userOrder, refundAmount,userOrderLines);
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("userOrderId",userOrder.getUserOrderId());
        jsonObject.put("userId",userOrder.getUserId());
        rabbitMQSender.sendRefundMoney(jsonObject);
    }

    public void refundMoney(long userOrderId, UserOrder userOrder, BigDecimal refundAmount,List<UserOrderLine> userOrderLines) throws ApiServiceException {
        BigDecimal planRefundAmount=refundAmount;
        BigDecimal amount = userOrder.getAmount();//订单金额
        long userId = userOrder.getUserId();
        BigDecimal actualAmount = userOrder.getActualAmount();//订单实际支付金额
        BigDecimal receiveAmount = userOrder.getReceiveAmount();//订单实际支付金额
        long discountId = userOrder.getDiscountId();//优惠券Id
        int payType = userOrder.getPayType();//支付方式
        BigDecimal companyPayAmount = userOrder.getCompanyPayAmount();//企业付金额
        BigDecimal familyPayAmount = userOrder.getFamilyPayAmount();//亲密付金额
        BigDecimal discountAmount = userOrder.getDiscountAmount();//优惠券金额
        //优惠券金额
        boolean isDiscount = discountAmount.compareTo(BigDecimal.ZERO) == 1 && userOrder.getDiscountId() > 0;
        if (isDiscount) {
            couponRefund(discountId, userOrderId);
            planRefundAmount = planRefundAmount.subtract(discountAmount);
            if (planRefundAmount.compareTo(BigDecimal.ZERO) == -1 || planRefundAmount.compareTo(BigDecimal.ZERO) == 0) {
                //修改订单状态status=-4
                if (userOrderDao.stepRefundUserOrder(userOrderId) <= 0) {
                    throw new ApiServiceException("订单退款失败，请重试");
                }
                //插入表
                OrderRefund orderRefund=new OrderRefund();
                orderRefundInsert(orderRefund,userOrderId,userOrder,refundAmount);
                orderRefund.setActualRefundAmount(BigDecimal.valueOf(0));
                orderRefund.setRefundCompanyPayAmount(BigDecimal.valueOf(0));
                orderRefund.setRefundFamilyPayAmount(BigDecimal.valueOf(0));
                if(userOrderDao.insertOrderRefund(orderRefund)<=0){
                    throw new ApiServiceException("订单退款失败，请重试");
                }
                for (UserOrderLine userOrderLine : userOrderLines) {
                    OrderdetailRefund orderdetailRefund = orderdetailRefundInsert(userOrderLine, orderRefund);
                    if(orderdetailRefund==null){
                        continue;
                    }
                    if(userOrderDao.insertOrderdetailRefund(orderdetailRefund)<=0){
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                }
                sendRefundOrder(userOrder);
                return;
            }
        }
        //余额退款
        if (actualAmount.compareTo(BigDecimal.ZERO) == 1) {
            if (planRefundAmount.compareTo(actualAmount) == -1 || planRefundAmount.compareTo(actualAmount) == 0) {
                refundType(userOrderId, actualAmount, planRefundAmount, payType, userId);
                //插入表
                OrderRefund orderRefund=new OrderRefund();
                orderRefundInsert(orderRefund,userOrderId,userOrder,refundAmount);
                orderRefund.setActualRefundAmount(planRefundAmount);
                orderRefund.setRefundCompanyPayAmount(BigDecimal.valueOf(0));
                orderRefund.setRefundFamilyPayAmount(BigDecimal.valueOf(0));
                if(payType==2){
                    orderRefund.setRefundWxpayAmount(planRefundAmount);
                }else{
                    orderRefund.setRefundWxpayAmount(BigDecimal.valueOf(0));
                }
                if(payType==3){
                    orderRefund.setRefundAlipayAmount(planRefundAmount);
                }else {
                    orderRefund.setRefundAlipayAmount(BigDecimal.valueOf(0));
                }
                if(userOrderDao.insertOrderRefund(orderRefund)<=0){
                    throw new ApiServiceException("订单退款失败，请重试");
                }
                for (UserOrderLine userOrderLine : userOrderLines) {
                    OrderdetailRefund orderdetailRefund = orderdetailRefundInsert(userOrderLine, orderRefund);
                    if(orderdetailRefund==null){
                        continue;
                    }
                    if(userOrderDao.insertOrderdetailRefund(orderdetailRefund)<=0){
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                }
                sendRefundOrder(userOrder);
                logger.info("发送日志1"+userOrder.getUserOrderId());
                return;
            } else {
                refundType(userOrderId, actualAmount, actualAmount, payType, userId);
                planRefundAmount = planRefundAmount.subtract(actualAmount);
            }
        }else {
            if (userOrderDao.stepRefundUserOrder(userOrderId) <= 0) {
                throw new ApiServiceException("订单退款失败，请重试");
            }
        }
        //亲密付
        boolean isFamilyPay = familyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        if (isFamilyPay) {
            if (planRefundAmount.compareTo(familyPayAmount) == -1) {
                familyPayAmount = planRefundAmount;
            }
            familyRefund(userOrderId, userOrder.getCreateTime(), userId, familyPayAmount);
            //插入表
            OrderRefund orderRefund=new OrderRefund();
            orderRefundInsert(orderRefund,userOrderId,userOrder,refundAmount);
            orderRefund.setActualRefundAmount(familyPayAmount.add(actualAmount));
            orderRefund.setRefundFamilyPayAmount(familyPayAmount);
            if(payType==2){
                orderRefund.setRefundWxpayAmount(actualAmount);
            }else {
                orderRefund.setRefundWxpayAmount(BigDecimal.valueOf(0));
            }
            if(payType==3){
                orderRefund.setRefundAlipayAmount(actualAmount);
            }else {
                orderRefund.setRefundAlipayAmount(BigDecimal.valueOf(0));
            }
            if(userOrderDao.insertOrderRefund(orderRefund)<=0){
                throw new ApiServiceException("订单退款失败，请重试");
            }
            for (UserOrderLine userOrderLine : userOrderLines) {
                OrderdetailRefund orderdetailRefund = orderdetailRefundInsert(userOrderLine, orderRefund);
                if(orderdetailRefund==null){
                    continue;
                }
                if(userOrderDao.insertOrderdetailRefund(orderdetailRefund)<=0){
                    throw new ApiServiceException("订单退款失败，请重试");
                }
            }
        }
        //企业付款
        boolean isCompanyPay = companyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        if (isCompanyPay) {
            if (planRefundAmount.compareTo(companyPayAmount) == -1) {
                companyPayAmount=planRefundAmount;
            }
            companyRefund(userOrderId, userId, companyPayAmount);
            //插入表
            OrderRefund orderRefund=new OrderRefund();
            orderRefundInsert(orderRefund,userOrderId,userOrder,refundAmount);
            orderRefund.setActualRefundAmount(companyPayAmount.add(actualAmount));
            orderRefund.setRefundCompanyPayAmount(companyPayAmount);
            if(payType==2){
                orderRefund.setRefundWxpayAmount(actualAmount);
            }else {
                orderRefund.setRefundWxpayAmount(BigDecimal.valueOf(0));
            }
            if(payType==3){
                orderRefund.setRefundAlipayAmount(actualAmount);
            }else {
                orderRefund.setRefundAlipayAmount(BigDecimal.valueOf(0));
            }
            if(userOrderDao.insertOrderRefund(orderRefund)<=0){
                throw new ApiServiceException("订单退款失败，请重试");
            }
            for (UserOrderLine userOrderLine : userOrderLines) {
                OrderdetailRefund orderdetailRefund = orderdetailRefundInsert(userOrderLine, orderRefund);
                if(orderdetailRefund==null){
                    continue;
                }
                if(userOrderDao.insertOrderdetailRefund(orderdetailRefund)<=0){
                    throw new ApiServiceException("订单退款失败，请重试");
                }
            }
        }
        sendRefundOrder(userOrder);
        logger.info("发送日志2"+userOrder.getUserOrderId());
    }
    public void orderRefundInsert(OrderRefund orderRefund,long userOrderId,UserOrder userOrder,BigDecimal refundAmount){
        long orderRefundId = IDGenerator.generateById("orderRefundId", userOrderId);
        orderRefund.setOrderRefundId(orderRefundId);
        orderRefund.setOrderId(userOrderId);
        orderRefund.setJoinCompanyId(userOrder.getJoinCompanyId());
        orderRefund.setMachineId(userOrder.getMachineId());
        orderRefund.setUserId(userOrder.getUserId());
        orderRefund.setStatus(1);
        orderRefund.setPlanRefundAmount(refundAmount);
        orderRefund.setRefundDiscountAmount(userOrder.getDiscountAmount());
    }
    public OrderdetailRefund orderdetailRefundInsert(UserOrderLine userOrderLine,OrderRefund orderRefund){
        if(userOrderLine.getRefundQuantity()==0){
            return null;
        }
        OrderdetailRefund orderdetailRefund=new OrderdetailRefund();
        long orderDetailRefundId=IDGenerator.generateById("orderDetailRefundId",orderRefund.getOrderRefundId());
        orderdetailRefund.setOrderDetailRefundId(orderDetailRefundId);
        orderdetailRefund.setOrderId(userOrderLine.getUserOrderId());
        orderdetailRefund.setOrderRefundId(orderRefund.getOrderRefundId());
        orderdetailRefund.setProductGlobalId(userOrderLine.getProductGlobalId());
        orderdetailRefund.setQuantity(userOrderLine.getQuantity());
        orderdetailRefund.setActualRefundQuantity(userOrderLine.getRefundQuantity());
        orderdetailRefund.setPrice(userOrderLine.getPrice());
//        orderdetailRefund.setRemark("优惠券退款");
        return orderdetailRefund;
    }
    public void sendRefundOrder(UserOrder userOrder) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("userOrderId",userOrder.getUserOrderId());
        jsonObject.put("machineId",userOrder.getMachineId());
        rabbitMQSender.sendRefundOrder(jsonObject);
    }
    public void refundType(long userOrderId,BigDecimal actualAmount,BigDecimal acAmount,int payType,long userId) throws ApiServiceException {
        if (actualAmount.compareTo(BigDecimal.ZERO) == 1) {
            switch (payType) {
                case 2://微信退款
                    try {
                        if (userOrderDao.refundUserOrder(userOrderId) <= 0) {
                            throw new ApiServiceException("订单退款失败，请重试");
                        }
                        WechatPay wechatPay = new WechatPay();
                        long capitalLogId = IDGenerator.generateById("userCapitalLogId", userOrderId);
                        wechatPay.refund(String.valueOf(userOrderId), String.valueOf(capitalLogId), actualAmount, acAmount, clientSetting.getPayCallback() + "/webapi/wechat/refundNotify");
                    } catch (Exception e) {
                        throw new ApiServiceException("微信退款失败");
                    }
                    break;
                case 3://支付宝退款
                    if (userOrderDao.refundUserOrder(userOrderId) <= 0) {
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                    logger.info("支付宝订单状态修改-1");
                    try {
                        AliPay aliPay = new AliPay();
                        aliPay.refund(String.valueOf(userOrderId), acAmount);
                    } catch (AlipayApiException e) {
                        throw new ApiServiceException("支付宝退款失败");
                    }
                    break;
                case 4://余额退款
                    balanceRefund(userId, userOrderId, acAmount);
                    //修改订单状态status=-4
                    if (userOrderDao.stepRefundUserOrder(userOrderId) <= 0) {
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                    break;
                default:
                    throw new ApiServiceException("支付方式选择错误");
            }
        }
    }
    //订单退款全部
    public void refund(long userOrderId, UserOrder userOrder) throws ApiServiceException {
        BigDecimal amount = userOrder.getAmount();//订单金额
        long userId = userOrder.getUserId();
        BigDecimal actualAmount = userOrder.getActualAmount();//订单实际支付金额
        BigDecimal receiveAmount = userOrder.getReceiveAmount();//订单实际支付金额
        long discountId = userOrder.getDiscountId();//优惠券Id
        int payType = userOrder.getPayType();//支付方式
        BigDecimal companyPayAmount = userOrder.getCompanyPayAmount();//企业付金额
        BigDecimal familyPayAmount = userOrder.getFamilyPayAmount();//亲密付金额
        BigDecimal discountAmount = userOrder.getDiscountAmount();//优惠券金额
        //企业付款
        boolean isCompanyPay = companyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        if (isCompanyPay) {
            companyRefund(userOrderId, userId, companyPayAmount);
        }
        //亲密付
        boolean isFamilyPay = familyPayAmount.compareTo(BigDecimal.ZERO) == 1;
        if (isFamilyPay) {
            familyRefund(userOrderId, userOrder.getCreateTime(), userId, familyPayAmount);
        }
        //优惠券金额
        boolean isDiscount = discountAmount.compareTo(BigDecimal.ZERO) == 1 && userOrder.getDiscountId() > 0;
        if (isDiscount) {
            couponRefund(discountId, userOrderId);
        }

        if (actualAmount.compareTo(BigDecimal.ZERO) == 1) {
            switch (payType) {
                case 2://微信退款
                    try {
                        if (userOrderDao.refundUserOrder(userOrderId) <= 0) {
                            throw new ApiServiceException("订单退款失败，请重试");
                        }
                        WechatPay wechatPay = new WechatPay();
                        long capitalLogId = IDGenerator.generateById("userCapitalLogId", userOrderId);
                        wechatPay.refund(String.valueOf(userOrderId), String.valueOf(capitalLogId), actualAmount, actualAmount, clientSetting.getPayCallback() + "/webapi/wechat/refundNotify");
                    } catch (Exception e) {
                        throw new ApiServiceException("微信退款失败");
                    }
                    break;
                case 3://支付宝退款
                    if (userOrderDao.refundUserOrder(userOrderId) <= 0) {
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                    try {
                        AliPay aliPay = new AliPay();
                        aliPay.refund(String.valueOf(userOrderId), actualAmount);
                    } catch (AlipayApiException e) {
                        throw new ApiServiceException("支付宝退款失败");
                    }
                    break;
                case 4://余额退款
                    balanceRefund(userId, userOrderId, actualAmount);
                    if (userOrderDao.refundedUserOrder(userOrderId) <= 0) {
                        throw new ApiServiceException("订单退款失败，请重试");
                    }
                    break;
                default:
                    throw new ApiServiceException("支付方式选择错误");
            }
        } else {
            if (userOrderDao.refundedUserOrder(userOrderId) <= 0) {
                throw new ApiServiceException("订单退款失败，请重试");
            }
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("userOrderId", userOrderId);
        jsonObject.put("machineId", userOrder.getMachineId());
        rabbitMQSender.sendRefundOrder(jsonObject);
        logger.info("打印日志3"+userOrderId);
    }

    //退款通知
    @Transactional(rollbackFor = ApiServiceException.class)
    public void refundNotify(long userOrderId, long capitalLogId, BigDecimal amount, String tradeNo, int payType) throws ApiServiceException {
        refundedUserOrder(userOrderId, capitalLogId, amount, tradeNo, payType);
    }

    private UserOrder refundedUserOrder(long userOrderId, long capitalLogId, BigDecimal amount, String tradeNo, int payType) throws ApiServiceException {
        logger.info("------------------------------000--------------------------------------------------------");
        if (userOrderId <= 0) {
            throw new ApiServiceException("订单id不能为空");
        }
        if (amount.compareTo(BigDecimal.ZERO) != 1) {
            throw new ApiServiceException("退款金额错误");
        }
        if (StringUtils.isNullOrEmpty(tradeNo)) {
            throw new ApiServiceException("交易流水号不能为空");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder == null) {
            throw new ApiServiceException("支付的订单不存在"+userOrderId);
        }
        logger.info("准备修改订单状态"+amount);
        if (userOrder.getStatus() != -1) {
            logger.info("查看订单状态"+userOrder.getStatus());
            return userOrder;
        }
        OrderRefund orderRefund=userOrderDao.getOrderRefund(userOrderId);
        logger.info("准备修改订单状态3"+amount);
        if(orderRefund==null){
            if (userOrder.getActualAmount().compareTo(amount) != 0) {
                logger.info("订单金额错误2");
                throw new ApiServiceException("订单金额错误");
            }
            if (userOrderDao.refundedUserOrder(userOrderId) <= 0) {
                logger.info("订单状态改为-3失败");
                throw new ApiServiceException("订单退款失败");
            }
            logger.info("订单状态改为-3");
            userOrder.setStatus(-3);
        }else{
            logger.info("------------------------------111--------------------------------------------------------");
            if((orderRefund.getRefundWxpayAmount().compareTo(amount)!=0)&&(orderRefund.getRefundAlipayAmount().compareTo(amount)!=0)){
                logger.info("订单金额局部退款错误"+amount);
                throw new ApiServiceException("订单金额局部退款错误");
            }
            logger.info(""+amount);
            logger.info("------------------------------222--------------------------------------------------------");
            if (userOrderDao.stepRefundUserOrder(userOrderId) <= 0) {
                logger.info("订单退款失败，请重试");
                throw new ApiServiceException("订单退款失败，请重试");
            }
            logger.info("------------------------------333--------------------------------------------------------");
            userOrder.setStatus(-4);
        }
        String payTypeName = "";
        String payTypeDescription = "";
        if (payType == 2) {
            payTypeName = "wechat";
            payTypeDescription = "微信退款";
        } else {
            payTypeName = "alipay";
            payTypeDescription = "支付宝退款";
        }
        if (userDao.insertUserCapital(capitalLogId, userOrder.getUserId(), amount, payTypeName, userOrderId, payTypeDescription) <= 0) {
            throw new ApiServiceException("插入资金流水失败");
        }
        if (userOrderDao.refundUserOrderCapital(userOrderId, payType, amount) <= 0) {
            throw new ApiServiceException("退款变更订单资金记录失败");
        }
        return userOrder;
    }

    //插入微信分步退款流水
    @Transactional(rollbackFor = ApiServiceException.class)
    public void insertWxuserorderrefund(Map<String,String> requltMap,Map<String,String> map) throws ApiServiceException {
        Wxuserorderrefund wxuserorderrefund=new Wxuserorderrefund();
        long reqId = IDGenerator.generate("reqId");
        wxuserorderrefund.setReqId(reqId);
        wxuserorderrefund.setReturnCode(requltMap.get("return_msg"));
        wxuserorderrefund.setReturnMsg(requltMap.get("return_code"));
        wxuserorderrefund.setAppid( requltMap.get("appid"));
        wxuserorderrefund.setMchId(requltMap.get("mch_id"));
        wxuserorderrefund.setNonceStr(requltMap.get("nonce_str"));
        wxuserorderrefund.setReqInfo(requltMap.get("req_info"));
        wxuserorderrefund.setTransactionId(map.get("transaction_id"));
        wxuserorderrefund.setOutTradeNo(map.get("out_trade_no"));
        wxuserorderrefund.setRefundId(map.get("refund_id"));
        wxuserorderrefund.setOutRefundNo(map.get("out_refund_no"));
        wxuserorderrefund.setTotalFee(Integer.parseInt(map.get("total_fee")));
        wxuserorderrefund.setSettlementTotalFee(Integer.parseInt(map.get("settlement_total_fee")));
        wxuserorderrefund.setRefundFee(Integer.parseInt(map.get("refund_fee")));
        wxuserorderrefund.setSettlementRefundFee(Integer.parseInt(map.get("settlement_refund_fee")));
        wxuserorderrefund.setRefundStatus(map.get("refund_status"));
        wxuserorderrefund.setSuccessTime(map.get("success_time"));
        wxuserorderrefund.setRefundRecvAccout(map.get("refund_recv_accout"));
        wxuserorderrefund.setRefundAccount(map.get("refund_account"));
        wxuserorderrefund.setRefundRequestSource(map.get("refund_request_source"));
        if(userOrderDao.insertWxuserorderrefund(wxuserorderrefund)<=0){
            throw new ApiServiceException("插入微信资金流水失败");
        }
    }
    public void insertAlipayuserorderrefund(Map<String,String> param) throws ApiServiceException, ParseException {
        Alipayuserorderrefund alipayuserorderrefund=new Alipayuserorderrefund();
        long reqId = IDGenerator.generate("reqId");
        alipayuserorderrefund.setReqId(reqId);
        alipayuserorderrefund.setCode(param.get("code"));
        alipayuserorderrefund.setMsg(param.get("msg"));
        alipayuserorderrefund.setSubCode(param.get("sub_code"));
        alipayuserorderrefund.setSubMsg(param.get("sub_msg"));
        alipayuserorderrefund.setSign(param.get("sign"));
        alipayuserorderrefund.setTradeNo(param.get("trade_no"));
        alipayuserorderrefund.setOutTradeNo(param.get("out_trade_no"));
        alipayuserorderrefund.setBuyerLogonId(param.get("buyer_logon_id"));
        alipayuserorderrefund.setFundChange(param.get("fund_change"));
        alipayuserorderrefund.setRefundFee(param.get("refund_fee"));
        alipayuserorderrefund.setRefundCurrency(param.get("refund_currency"));
//        alipayuserorderrefund.setGmtRefundPay(DateUtils.StringToDate(param.get("gmt_refund_pay")));
        alipayuserorderrefund.setRefundDetailItemList(param.get("refund_detail_item_list"));
        alipayuserorderrefund.setStoreName(param.get("store_name"));
        alipayuserorderrefund.setBuyerUserId(param.get("buyer_user_id"));
        alipayuserorderrefund.setRefundPresetPaytoolList(param.get("refund_preset_paytool_list"));
        alipayuserorderrefund.setRefundSettlementId(param.get("refund_settlement_id"));
        alipayuserorderrefund.setPresentRefundBuyerAmount(param.get("present_refund_buyer_amount"));
        alipayuserorderrefund.setPresentRefundDiscountAmount(param.get("present_refund_discount_amount"));
        alipayuserorderrefund.setPresentRefundMdiscountAmount(param.get("present_refund_mdiscount_amount"));
        if(userOrderDao.insertAlipayuserorderrefund(alipayuserorderrefund)<=0){
            throw new ApiServiceException("插入支付宝资金流水失败");
        }
    }
    /**
     * 获取订单列表
     *
     * @param userId
     * @param status
     * @return
     */
    public List<UserOrder> getUserOrders(long userId, int status, int pageStart, int pageSize) throws ApiServiceException {
        switch (status) {
            case 0:
                return userOrderDao.getUserOrders(userId, pageSize, pageStart);
            case 1:
                return userOrderDao.getPayUserOrders(userId, pageSize, pageStart);
            case 3:
                return userOrderDao.getPaidUserOrders(userId, pageSize, pageStart);
            case 5:
                return userOrderDao.getAssessUserOrders(userId, pageSize, pageStart);
            case 7:
                return userOrderDao.getInvalidUserOrders(userId, pageSize, pageStart);
            default:
                throw new ApiServiceException("参数数据错误");
        }
    }

    //获取机器里的取餐订单
    public Data getMachineTakeUserOrder(long userId, long machineId) throws ApiServiceException {
        String key = "ClineService:ScanCode_" + machineId + "_" + userId;
        if (!redisUtils.exists(key)) {
            throw new ApiServiceException(1006, "二维码过期，请重新扫码");
        }
        Data machineData = new Data();
        Machine machine = machineDao.getMachine(machineId);
        if (machine == null) {
            throw new ApiServiceException("该机器不存在");
        }
        machineData.put("machineId", machineId);
        machineData.put("address", machine.getAddress());
        machineData.put("machineName", machine.getMachineName());
        machineData.put("putMachineName", machine.getPutMachineName());

        List<Data> userOrderDatas = new ArrayList<>();
        List<UserOrder> userOrders = userOrderDao.getTakeUserOrders(userId, machineId);
        for (UserOrder userOrder : userOrders) {
            Data userOrderData = new Data();
            userOrderData.put("payTime", DateUtils.DateToString(userOrder.getPayTime()));
            userOrderData.put("userOrderId", userOrder.getUserOrderId());
            userOrderData.put("description", userOrder.getDescription());
            userOrderData.put("status", userOrder.getStatus());
            userOrderDatas.add(userOrderData);
        }
        Data data = new Data();
        data.put("machine", machineData);
        data.put("userOrders", userOrderDatas);
        return data;
    }

    //扫机器
    public long scanMachine(long userId, long machineId) throws ApiServiceException {
        if (machineId <= 0) {
            throw new ApiServiceException("扫机器参数异常");
        }
        Machine machine = machineDao.getMachine(machineId);
        if (machine == null) {
            throw new ApiServiceException("无法找到该机器");
        }
        if (machine.getStatus() != 7 && machine.getStatus() != 5) {
            //机器不在运行中或者调试中
            throw new ApiServiceException("机器没有运行，无法扫码取餐");
        }
        String key = "ClineService:ScanCode_" + machineId + "_" + userId;
        redisUtils.set(key, machineId + ":" + userId);
        redisUtils.expire(key, 60 * 20);//20分钟失效
        return machineId;
    }

    //取商品／取餐
    @Transactional(rollbackFor = ApiServiceException.class)
    public ImagesShare takeProduct(long userId, long machineId, long userOrderId) throws ApiServiceException {
        if (machineId <= 0 || userOrderId <= 0) {
            throw new ApiServiceException("取餐参数异常");
        }
        String key = "ClineService:ScanCode_" + machineId + "_" + userId;
        if (!redisUtils.exists(key)) {
            throw new ApiServiceException(1006, "二维码过期，请重新扫码");
        }
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder == null) {
            throw new ApiServiceException("该用户订单不存在");
        }
        if (userOrder.getMachineId() != machineId) {
            throw new ApiServiceException("取餐机器选择错误");
        }
        if (userOrder.getStatus() != 3) {
            throw new ApiServiceException("订单不在取餐状态,请刷新重试");
        }
        if (userOrder.getInvalidTime().compareTo(new Date()) == -1) {
            throw new ApiServiceException("订单已过期，无法进行取餐操作");
        }
        try {
            generateUserOrderProductLines(userOrderId, userId);
            //根据用的成长值获取积分特权
            User user = userDao.getUserGradeValue(userId);
            long gradeValue = user.getGradeValue();
            Data data = userDao.getUserGradePrivilege(gradeValue, 1);
            double multiple = Double.valueOf(data.get("content").toString());
            BigDecimal m = userOrder.getActualAmount().multiply(BigDecimal.valueOf(multiple));
            int integral = (int) Math.ceil(m.doubleValue());

            //用户积分增长
            if (userDao.addIntegral(userId, integral) <= 0) {
                throw new ApiServiceException("更新积分失败");
            }
            //用户积分日志
            long integralLogId = IDGenerator.generateById("integralLogId", userId);
            if (userDao.insertIntegralLog(integralLogId, userId, integral, "assessUserOrder", userOrderId, "用户下单取餐获取积分") <= 0) {
                throw new ApiServiceException("添加积分日志失败");
            }
            //用户成长值增长
            if (userDao.updateGradeGrowUp(userId, integral) <= 0) {
                throw new ApiServiceException("更新用户成长值失败");
            }
            //用户成长日志
            long userGradeLogId = IDGenerator.generateById("userGradeLogId", userId);
            if (userDao.insertUserGradeLog(userGradeLogId, userId, integral, "userSignIn", userId, "用户下单取餐获取成长值") <= 0) {
                throw new ApiServiceException("添加用户成长记录失败");
            }
            //获取分享图片
            ImagesShare imagesShare = userOrderDao.getImagesShare();
            if (null == imagesShare) {
                throw new ApiServiceException("分享图片未加载");
            }
            imagesShare.setShareImage(commonFun.sourceImage(imagesShare.getShareImage()));
            imagesShare.setShowImage(commonFun.sourceImage(imagesShare.getShowImage()));
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("machineId", machineId);
            jsonObject.put("orderId", userOrderId);
            rabbitMQSender.takeFood(jsonObject);
            return imagesShare;
        } catch (TimeoutException e) {
            throw new ApiServiceException("取餐失败，请重试");
        } catch (IOException e) {
            throw new ApiServiceException("取餐失败，请重试");
        }
    }

    //评价订单
    @Transactional(rollbackFor = ApiServiceException.class)
    public void assessUserOrder(long userOrderId, long userId, List<UserOrderLine> userOrderLines) throws ApiServiceException {
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder == null) {
            throw new ApiServiceException("订单不存在");
        }
        if (userOrder.getStatus() < 6) {
            throw new ApiServiceException("订单还没取餐，不能评价");
        }
        if (userId != userOrder.getUserId()) {
            throw new ApiServiceException("当前用户不能评价该订单");
        }
        if (userOrderDao.assessUserOrder(userOrderId) <= 0) {
            throw new ApiServiceException("评价订单失败，请重试");
        }
        for (UserOrderLine userOrderLine : userOrderLines) {
            if (StringUtils.getWordCount(userOrderLine.getEvaluation()) > 100) {
                throw new ApiServiceException("评价内容限定100个字符");
            }
            if (userOrderDao.assessUserOrderLine(userOrderLine) <= 0) {
                throw new ApiServiceException("评价商品失败，请重试");
            }
        }
        //用户积分增长
        if (userDao.addIntegral(userId, 2) <= 0) {
            throw new ApiServiceException("更新积分失败");
        }
        //用户积分日志
        long integralLogId = IDGenerator.generateById("integralLogId", userId);
        if (userDao.insertIntegralLog(integralLogId, userId, 2, "assessUserOrder", userOrderId, "用户评价订单获取积分") <= 0) {
            throw new ApiServiceException("添加积分日志失败");
        }
        //用户成长值增长
        if (userDao.updateGradeGrowUp(userId, 2) <= 0) {
            throw new ApiServiceException("更新用户成长值失败");
        }
        //用户成长日志
        long userGradeLogId = IDGenerator.generateById("userGradeLogId", userId);
        if (userDao.insertUserGradeLog(userGradeLogId, userId, 2, "userSignIn", userId, "用户评价订单获取成长值") <= 0) {
            throw new ApiServiceException("添加用户成长记录失败");
        }
    }

    //获取待支付订单
    public List<UserOrder> getToPayUserOrder(long userOrderId) {
        return userOrderDao.getToPayUserOrder(userOrderId);
    }

    /**
     * 获取订单详情数据
     *
     * @return
     */
    public Data getUserOrder(long userOrderId) throws ParseException, ApiServiceException {
        Data data = new Data();
        Data userOrder = userOrderDao.getUserOrderDetail(userOrderId);
        int status = Integer.parseInt(userOrder.get("status").toString());
        Date createTime = DateUtils.StringToDate(userOrder.get("createTime").toString());
        if (status == 2) {
            userOrder.put("invalidTime", DateUtils.getAfterTime(createTime, 3));
        }
        String amountstr = userOrder.get("amount").toString();
        BigDecimal amountBD = new BigDecimal(amountstr);
        amountBD = amountBD.setScale(2, BigDecimal.ROUND_HALF_UP);
        String amount = amountBD.toString();
        userOrder.put("amount", amount);

        String actualAmountstr = userOrder.get("actualAmount").toString();
        BigDecimal actualAmountBD = new BigDecimal(actualAmountstr);
        actualAmountBD = actualAmountBD.setScale(2, BigDecimal.ROUND_HALF_UP);
        String actualAmount = actualAmountBD.toString();
        userOrder.put("actualAmount", actualAmount);
        String sp = String.valueOf(userOrder.get("seekPhotos"));
        if (!StringUtils.isNullOrEmpty(sp)) {
            userOrder.put("seekPhotos", sp);
        }
       /* String[] seekPhotos = null;
        if(!StringUtils.isNullOrEmpty(sp)){
            String[] strs = sp.split(";");
            int j = 0;
            for (int i = 0; i < strs.length; i++) {
                if (!StringUtils.isNullOrEmpty(strs[i])) {
                    j++;
                }
            }
            seekPhotos = new String[j];
            int t=0;
            for (int i = 0; i <strs.length; i++) {
                if(!StringUtils.isNullOrEmpty(strs[i])){
                    seekPhotos[t] = commonFun.sourceImage(strs[i]);
                    t++;
                }
            }
            userOrder.put("seekPhotos",seekPhotos);
        }*/
        String refundAmount="";
        if(status==-4){
            OrderRefund orderRefund=userOrderDao.getOrderRefund(userOrderId);
            if (orderRefund==null){
                throw new ApiServiceException("订单不存在");
            }
            BigDecimal actualRefundAmount = orderRefund.getActualRefundAmount();
            BigDecimal refundFamilyPayAmount = orderRefund.getRefundFamilyPayAmount();
            BigDecimal refundCompanyPayAmount = orderRefund.getRefundCompanyPayAmount();

            BigDecimal refundAmountBd=actualRefundAmount.subtract(refundCompanyPayAmount).subtract(refundFamilyPayAmount);
            refundAmountBd=refundAmountBd.setScale(2,BigDecimal.ROUND_HALF_UP);
            refundAmount= refundAmountBd.toString();

            refundFamilyPayAmount.setScale(2,BigDecimal.ROUND_HALF_UP);
            String familyPayAmount = refundFamilyPayAmount.toString();
            userOrder.put("familyPayAmount",familyPayAmount);

            refundCompanyPayAmount.setScale(2,BigDecimal.ROUND_HALF_UP);
            String companyPayAmount = refundCompanyPayAmount.toString();
            userOrder.put("companyPayAmount",companyPayAmount);

            BigDecimal refundDiscountAmount = orderRefund.getRefundDiscountAmount();
            refundDiscountAmount.setScale(2,BigDecimal.ROUND_HALF_UP);
            String discountAmount = refundDiscountAmount.toString();
            userOrder.put("discountAmount",discountAmount);

            long orderRefundId = orderRefund.getOrderRefundId();
            userOrder.put("orderRefundId",orderRefundId);
        }else {
            refundAmount=actualAmount;
        }
        userOrder.put("refundAmount",refundAmount);
        data.put("userOrder", userOrder);
        List<Data> userOrderLines = userOrderDao.getUserOrderLineAndProducts(userOrderId);
        for (Data userOrderLine : userOrderLines) {
            String pricestr = userOrderLine.get("price").toString();
            BigDecimal priceBD = new BigDecimal(pricestr);
            priceBD = priceBD.setScale(2, BigDecimal.ROUND_HALF_UP);
            userOrderLine.put("price", priceBD.toString());
        }
        data.put("userOrderLines", userOrderLines);
        return data;
    }

    //获取取餐详情
    public Data getUserOrderTakeDetail(long userOrderId) throws ApiServiceException {
        Data result = new Data();
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        if (userOrder == null) {
            throw new ApiServiceException("订单不存在");
        }
//        if(userOrder.getStatus()!=4){
//            throw new ApiServiceException("订单还没进入取餐过程，请稍后查看");
//        }
        result.put("userOrderStatus", userOrder.getStatus());
        result.put("takeNo", userOrder.getTakeNo());
        result.put("productLines", userOrderDao.getUserOrderProductLines(userOrderId));
        return result;
    }

    //获取单个商品的取餐情况
    public Data getUserOrderProductLine(long userOrderProductLineId) {
        return userOrderDao.getUserOrderProductLine(userOrderProductLineId);
    }

    //获取单个商品的取餐情况
    public Data getAssessUserOrderLineAndProducts(long userOrderId) {
        UserOrder userOrder = userOrderDao.getUserOrder(userOrderId);
        List<Data> datas = userOrderDao.getAssessUserOrderLineAndProducts(userOrderId);
        for (Data data : datas) {
            String attachImageUrls = data.get("attachImageUrls").toString();
            if (!StringUtils.isNullOrEmpty(attachImageUrls)) {
                String[] urls = attachImageUrls.split(";");
                data.put("imageUrl", commonFun.sourceImage(urls[0]));
            }
        }
        //获取分享图片
        ImagesShare imagesShare = userOrderDao.getImagesShare();
        imagesShare.setShareImage(commonFun.sourceImage(imagesShare.getShareImage()));
        imagesShare.setShowImage(commonFun.sourceImage(imagesShare.getShowImage()));
        Data data = new Data();
        data.put("userOrder", userOrder);
        data.put("userOrderLines", datas);
        data.put("imagesShare", imagesShare);
        return data;
    }

    public List<UserOrderLine> getUserOrderLine(long userOrderId) {
        return userOrderDao.getUserOrderLines(userOrderId);
    }

    /**
     * 取餐提醒
     */
    @Transactional(rollbackFor = ApiServiceException.class)
    public void sendTemplateClaim(Long userOrderId, String openId, String quantity, String machineName, String takeNo, String templateId, String firsMessage) throws ApiServiceException {
        // 获取基础支持的access_token
        String access_token = wechatAuth.getBasicAccessToken();
       /* BasicAccessToken basicAccessToken= wechatAuth.getBasicAccessToken();
        String basicAccessTokenStr=JsonUtils.GsonString(basicAccessToken);
        Map<String,String> map=JsonUtils.GsonToMaps(basicAccessTokenStr);
        String access_token=map.get("access_token");*/
        // 发送模板消息
        String resultUrl2 = "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=" + access_token;
        // 封装基础数据
        WechatTemplate wechatTemplate = new WechatTemplate();
        wechatTemplate.setTemplate_id(templateId);
        wechatTemplate.setTouser(openId);
        Map<String, TemplateData> mapdata = new HashMap<>();
        // 封装模板数据
        TemplateData first = new TemplateData();
        first.setValue(firsMessage);
        first.setColor("#173177");
        mapdata.put("first", first);
        //出餐编号
        TemplateData keyword1 = new TemplateData();
        keyword1.setValue(takeNo);
        keyword1.setColor("#173177");
        mapdata.put("keyword1", keyword1);
        //出餐数量
        TemplateData keyword2 = new TemplateData();
        keyword2.setValue(quantity);
        keyword2.setColor("#173177");
        mapdata.put("keyword2", keyword2);
        //订单编号
        TemplateData keyword3 = new TemplateData();
        keyword3.setValue(Long.toString(userOrderId));
        keyword3.setColor("#173177");
        mapdata.put("keyword3", keyword3);
        //机器名称
        TemplateData keyword4 = new TemplateData();
        keyword4.setValue(machineName);
        keyword4.setColor("#173177");
        mapdata.put("keyword4", keyword4);

        TemplateData remark = new TemplateData();
        remark.setValue("如有疑问，请拨打伍饭宝咨询热线400-755-557");
        remark.setColor("#173177");
        mapdata.put("remark", remark);

        wechatTemplate.setData(mapdata);
        String toString = JsonUtils.GsonString(wechatTemplate);
        String json2 = HttpUtil.post(resultUrl2, toString);
        TemplateResult templateResult = JsonUtils.GsonToBean(json2, TemplateResult.class);
        if (null != templateResult) {
            if (0 != templateResult.getErrcode()) {
                logger.info(String.valueOf(templateResult.getErrcode()));
                logger.info(templateResult.toString());
                throw new ApiServiceException("消息推送失败" + templateResult.getErrmsg());
            }
        }
    }

    @Transactional(rollbackFor = ApiServiceException.class)
    public List<Data> getOverdueUserOrder() throws ApiServiceException {
        return userOrderDao.getOverdueUserOrder();
    }

    @Transactional(rollbackFor = ApiServiceException.class)
    public int updateMessageStatus(long userOrderId) throws ApiServiceException {
        return userOrderDao.updateMessageStatus(userOrderId);
    }

    //获取支付来源状态值
    public Data getMessage(long userOrderId) {
        return userOrderDao.getMessage(userOrderId);
    }

    //已取餐
    @Transactional(rollbackFor = ApiServiceException.class)
    public void sendTemplateTake(Long userOrderId, String openId, String quantity, String machineName, String templateId) throws ApiServiceException {
        // 获取基础支持的access_token
        String access_token = wechatAuth.getBasicAccessToken();
        // 发送模板消息
        String resultUrl2 = "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=" + access_token;
        // 封装基础数据
        WechatTemplate wechatTemplate = new WechatTemplate();
        wechatTemplate.setTemplate_id(templateId);
        if (StringUtils.isNullOrEmpty(openId)) {
            return;
        }
        wechatTemplate.setTouser(openId);
        Map<String, TemplateData> mapdata = new HashMap<>();
        // 封装模板数据
        TemplateData first = new TemplateData();
        first.setValue("取餐完成，祝您用餐愉快。");
        first.setColor("#173177");
        mapdata.put("first", first);
        //订单编号
        TemplateData keyword1 = new TemplateData();
        keyword1.setValue(Long.toString(userOrderId));
        keyword1.setColor("#173177");
        mapdata.put("keyword1", keyword1);
        //出餐数量
        TemplateData keyword2 = new TemplateData();
        keyword2.setValue(quantity);
        keyword2.setColor("#173177");
        mapdata.put("keyword2", keyword2);
        //机器名称
        TemplateData keyword3 = new TemplateData();
        keyword3.setValue(machineName);
        keyword3.setColor("#173177");
        mapdata.put("keyword3", keyword3);

        TemplateData remark = new TemplateData();
        remark.setValue("如有疑问，请拨打伍饭宝咨询热线400-755-557");
        remark.setColor("#173177");
        mapdata.put("remark", remark);
        wechatTemplate.setData(mapdata);

        String toString = JsonUtils.GsonString(wechatTemplate);
        String json2 = HttpUtil.post(resultUrl2, toString);
        TemplateResult templateResult = JsonUtils.GsonToBean(json2, TemplateResult.class);
        if (null != templateResult) {
            if (0 != templateResult.getErrcode()) {
                logger.info(templateResult.toString());
                throw new ApiServiceException("消息推送失败" + templateResult.getErrmsg());
            }
        }
    }

    //获取订单数据
    public UserOrder findUserOrder(long userOrderId) {
        return userOrderDao.findUserOrder(userOrderId);
    }

    //获取手机号
    public String getUserMb(long userOrderId) {
        String mb = userOrderDao.getUserMb(userOrderId);
        if (StringUtils.isNullOrEmpty(mb)) {
            return "";
        }
        return mb;
    }
    //修复保温柜库存
    @Transactional(rollbackFor = ApiServiceException.class)
    public void repaireProductPrepares(ProductPrepare productPrepare, long machineId) throws ApiServiceException {
        if (productDao.unLockProductPrepare(machineId, productPrepare.getProductGlobalId()) <= 0) {
            logger.error("更新预制作商品库存失败!" + machineId + "," + productPrepare.getProductGlobalId());
            throw new ApiServiceException("更新预制作商品库存失败!");
        }
        if (productDao.unLockMachineProductPrepare(machineId) <= 0) {
            logger.error("更新预制作机器库存失败!" + machineId);
            throw new ApiServiceException("更新预制作机器库存失败");
        }
        if(productDao.updateRepairStatus(productPrepare.getProductOffId())<=0){
            throw new ApiServiceException("更新预制做表失败");
        }
    }

    @Transactional(rollbackFor = ApiServiceException.class)
    public Data getOrderDetailRefund(long orderRefundId) throws ApiServiceException {
        List<Data> orderdetailRefund=userOrderDao.getOrderDetailRefund(orderRefundId);
        if (orderdetailRefund==null||orderdetailRefund.size()==0){
            throw new ApiServiceException("退款明细不存在");
        }
        Data data=new Data();
        BigDecimal total=BigDecimal.valueOf(0);
        for (Data refund : orderdetailRefund) {
            String priceStr=refund.get("price").toString();
            BigDecimal price = new BigDecimal(priceStr);
            String refundQuantityStr = refund.get("actualRefundquantity").toString();
            long refundQuantity = Long.parseLong(refundQuantityStr);
            total=price.multiply(BigDecimal.valueOf(refundQuantity)).add(total);
        }
        data.put("orderdetailRefund",orderdetailRefund);
        data.put("total",total);
        return data;
    }
}