package com.bdemo.sys.log.dto;

/**
 * 命名维度的统计（模块 / 用户 / 结果）。
 */
public class NameCount {

    private String name;
    private long total;
    private long fail;
    /** 失败率（0~100，保留两位小数） */
    private double failureRate;

    public NameCount() {
    }

    public NameCount(String name, long total, long fail, double failureRate) {
        this.name = name;
        this.total = total;
        this.fail = fail;
        this.failureRate = failureRate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
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
}
