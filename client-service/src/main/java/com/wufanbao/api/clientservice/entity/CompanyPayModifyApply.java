package com.wufanbao.api.clientservice.entity;

import java.util.Date;
import java.math.BigDecimal;


// CompanyPayModifyApply,公司支付更改申请
public class CompanyPayModifyApply {
    //PayModifyApplyId,
    private long payModifyApplyId;
    //CompanyId,
    private long companyId;
    //组织机构代码,
    private String organizationCode;
    //名称,
    private String companyName;
    //支付密码,
    private String payPassword;
    //营业执照,
    private String businessLicense;
    //身份证/其他有效证见正面,
    private String iDCardPositive;
    //身份证/其他有效证见正面反面,
    private String iDCardNegative;
    //状态,
    private AuditState status;
    //申请人,
    private long applyEmployeeId;
    //申请时间,
    private Date applyTime;
    //审核人,
    private long auditManagerId;
    //审核时间,
    private Date auditTime;
    //审核结果,
    private String auditResult;
    //失效时间,
    private Date invalidTime;

    public long getPayModifyApplyId() {
        return this.payModifyApplyId;
    }

    public void setPayModifyApplyId(long payModifyApplyId) {
        this.payModifyApplyId = payModifyApplyId;
    }

    public long getCompanyId() {
        return this.companyId;
    }

    public void setCompanyId(long companyId) {
        this.companyId = companyId;
    }

    public String getOrganizationCode() {
        return this.organizationCode;
    }

    public void setOrganizationCode(String organizationCode) {
        this.organizationCode = organizationCode;
    }

    public String getCompanyName() {
        return this.companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getPayPassword() {
        return this.payPassword;
    }

    public void setPayPassword(String payPassword) {
        this.payPassword = payPassword;
    }

    public String getBusinessLicense() {
        return this.businessLicense;
    }

    public void setBusinessLicense(String businessLicense) {
        this.businessLicense = businessLicense;
    }

    public String getIDCardPositive() {
        return this.iDCardPositive;
    }

    public void setIDCardPositive(String iDCardPositive) {
        this.iDCardPositive = iDCardPositive;
    }

    public String getIDCardNegative() {
        return this.iDCardNegative;
    }

    public void setIDCardNegative(String iDCardNegative) {
        this.iDCardNegative = iDCardNegative;
    }

    public AuditState getStatus() {
        return this.status;
    }

    public void setStatus(AuditState status) {
        this.status = status;
    }

    public long getApplyEmployeeId() {
        return this.applyEmployeeId;
    }

    public void setApplyEmployeeId(long applyEmployeeId) {
        this.applyEmployeeId = applyEmployeeId;
    }

    public Date getApplyTime() {
        return this.applyTime;
    }

    public void setApplyTime(Date applyTime) {
        this.applyTime = applyTime;
    }

    public long getAuditManagerId() {
        return this.auditManagerId;
    }

    public void setAuditManagerId(long auditManagerId) {
        this.auditManagerId = auditManagerId;
    }

    public Date getAuditTime() {
        return this.auditTime;
    }

    public void setAuditTime(Date auditTime) {
        this.auditTime = auditTime;
    }

    public String getAuditResult() {
        return this.auditResult;
    }

    public void setAuditResult(String auditResult) {
        this.auditResult = auditResult;
    }

    public Date getInvalidTime() {
        return this.invalidTime;
    }

    public void setInvalidTime(Date invalidTime) {
        this.invalidTime = invalidTime;
    }

}
