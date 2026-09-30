package com.bdemo.sys.log.dto;

/**
 * 日志聚合查询的原始行（GROUP BY 结果）。
 */
public class StatRow {

    private String name;
    private long total;
    private long fail;

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
}
