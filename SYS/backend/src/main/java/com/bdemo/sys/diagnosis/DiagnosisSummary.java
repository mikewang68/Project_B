package com.bdemo.sys.diagnosis;

/**
 * 诊断汇总（字段与指令 §23 输出一致：failed / failureRate 为 0~1 小数）。
 */
public class DiagnosisSummary {

    private long total;
    private long success;
    private long failed;
    private double failureRate;

    public DiagnosisSummary() {
    }

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

    public long getFailed() {
        return failed;
    }

    public void setFailed(long failed) {
        this.failed = failed;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }
}
