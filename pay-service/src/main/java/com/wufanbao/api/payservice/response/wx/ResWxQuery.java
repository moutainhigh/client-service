package com.wufanbao.api.payservice.response.wx;

import com.wufanbao.api.payservice.common.ResponseBody;
import com.wufanbao.api.payservice.common.ResponseData;
import com.wufanbao.api.payservice.response.PayResponse;

public class ResWxQuery extends PayResponse {
    private String trade_no;
    private String wx_trade_no;
    private String trade_status;
    private double total_amount;
    private double coupon_free;
    private String send_pay_time;

    public String getTrade_no() {
        return trade_no;
    }

    public void setTrade_no(String trade_no) {
        this.trade_no = trade_no;
    }

    public String getWx_trade_no() {
        return wx_trade_no;
    }

    public void setWx_trade_no(String wx_trade_no) {
        this.wx_trade_no = wx_trade_no;
    }

    public String getTrade_status() {
        return trade_status;
    }

    public void setTrade_status(String trade_status) {
        this.trade_status = trade_status;
    }

    public double getTotal_amount() {
        return total_amount;
    }

    public void setTotal_amount(double total_amount) {
        this.total_amount = total_amount;
    }

    public double getCoupon_free() {
        return coupon_free;
    }

    public void setCoupon_free(double coupon_free) {
        this.coupon_free = coupon_free;
    }

    public String getSend_pay_time() {
        return send_pay_time;
    }

    public void setSend_pay_time(String send_pay_time) {
        this.send_pay_time = send_pay_time;
    }

    public static class Data {
        private ResponseBody responseBody;
        private ResponseData<ResWxQuery> responseData;


        public ResponseBody getResponseBody() {
            return responseBody;
        }

        public void setResponseBody(ResponseBody responseBody) {
            this.responseBody = responseBody;
        }

        public ResponseData<ResWxQuery> getResponseData() {
            return responseData;
        }

        public void setResponseData(ResponseData<ResWxQuery> responseData) {
            this.responseData = responseData;
        }

    }
}
