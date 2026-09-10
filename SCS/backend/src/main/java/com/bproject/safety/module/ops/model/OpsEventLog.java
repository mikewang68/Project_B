package com.bproject.safety.module.ops.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 运维事件日志（任务书第四十一节）：独立于 Alert Timeline，不混为一个对象。
 * 覆盖 node offline / node reconnect / clock mismatch / rule mismatch /
 * replay started / replay success / replay failed / duplicate skipped / node online。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpsEventLog {

    public String id;
    public OffsetDateTime ts;
    /** 展示用 HH:mm:ss。 */
    public String time;
    public String nodeId;
    /** info / warning / error。 */
    public String level;
    /** 稳定事件类型（见本类常量）。 */
    public String type;
    public String text;

    public static final String NODE_OFFLINE = "NODE_OFFLINE";
    public static final String NODE_RECONNECT = "NODE_RECONNECT";
    public static final String CLOCK_MISMATCH = "CLOCK_MISMATCH";
    public static final String CLOCK_RECONCILED = "CLOCK_RECONCILED";
    public static final String RULE_MISMATCH = "RULE_MISMATCH";
    public static final String RULE_RECONCILED = "RULE_RECONCILED";
    public static final String LOCAL_JUDGEMENT = "LOCAL_JUDGEMENT";
    public static final String REPLAY_STARTED = "REPLAY_STARTED";
    public static final String REPLAY_SUCCESS = "REPLAY_SUCCESS";
    public static final String REPLAY_FAILED = "REPLAY_FAILED";
    public static final String DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED";
    public static final String NODE_ONLINE = "NODE_ONLINE";
    public static final String DEVICE_FAULT = "DEVICE_FAULT";
    public static final String CACHE_WARNING = "CACHE_WARNING";

    public OpsEventLog() {
    }

    public OpsEventLog(String id, OffsetDateTime ts, String time, String nodeId, String level,
                       String type, String text) {
        this.id = id;
        this.ts = ts;
        this.time = time;
        this.nodeId = nodeId;
        this.level = level;
        this.type = type;
        this.text = text;
    }

    public OpsEventLog copy() {
        return new OpsEventLog(id, ts, time, nodeId, level, type, text);
    }
}
