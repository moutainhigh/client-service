package com.wufanbao.api.payservice.response.ali;

import com.wufanbao.api.payservice.common.ResponseBody;
import com.wufanbao.api.payservice.common.ResponseData;
import com.wufanbao.api.payservice.response.PayResponse;

public class ResAliPrePayQuery extends PayResponse {
    private String trade_no;
    private String trade_done;
    private String ali_trade_no;
    private double total_amount;
    private String body;
    private String pay_time;

    public String getTrade_no() {
        return trade_no;
    }

    public void setTrade_no(String trade_no) {
        this.trade_no = trade_no;
    }

    public String getTrade_done() {
        return trade_done;
    }

    public void setTrade_done(String trade_done) {
        this.trade_done = trade_done;
    }

    public String getAli_trade_no() {
        return ali_trade_no;
    }

    public void setAli_trade_no(String ali_trade_no) {
        this.ali_trade_no = ali_trade_no;
    }

    public double getTotal_amount() {
        return total_amount;
    }

    public void setTotal_amount(double total_amount) {
        this.total_amount = total_amount;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getPay_time() {
        return pay_time;
    }

    public void setPay_time(String pay_time) {
        this.pay_time = pay_time;
    }

    public static class Data {
        private ResponseBody responseBody;
        private ResponseData<ResAliPrePayQuery> responseData;


        public ResponseBody getResponseBody() {
            return responseBody;
        }

        public void setResponseBody(ResponseBody responseBody) {
            this.responseBody = responseBody;
        }

        public ResponseData<ResAliPrePayQuery> getResponseData() {
            return responseData;
        }

        public void setResponseData(ResponseData<ResAliPrePayQuery> responseData) {
            this.responseData = responseData;
        }

    }
}
