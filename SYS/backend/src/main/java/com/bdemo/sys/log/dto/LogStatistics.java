package com.bdemo.sys.log.dto;

import java.util.List;

/**
 * 操作日志统计概览。
 */
public class LogStatistics {

    private long total;
    private long success;
    private long fail;
    /** 失败率（0~100，保留两位小数） */
    private double failureRate;
    private List<NameCount> byModule;
    private List<NameCount> byUser;
    private List<NameCount> byResult;

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getSuccess() {
        return success;
    }

    public void setSuccess(long success) {
        this.success = success;
    }

    public long getFail() {
        return fail;
    }

    public void setFail(long fail) {
        this.fail = fail;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }

    public List<NameCount> getByModule() {
        return byModule;
    }

    public void setByModule(List<NameCount> byModule) {
        this.byModule = byModule;
    }

    public List<NameCount> getByUser() {
        return byUser;
    }

    public void setByUser(List<NameCount> byUser) {
        this.byUser = byUser;
    }

    public List<NameCount> getByResult() {
        return byResult;
    }

    public void setByResult(List<NameCount> byResult) {
        this.byResult = byResult;
    }
}
