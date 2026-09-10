package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import java.util.List;

/** 运维事件日志存储（独立于 Alert Timeline；不做 ELK，仅内存最近记录）。 */
public interface OpsEventLogRepository {

    OpsEventLog append(OpsEventLog log);

    /** 最近日志（倒序，最新在前），支持 nodeId / level / type 过滤。 */
    List<OpsEventLog> recent(Integer limit, String nodeId, String level, String type,
                             String from, String to);

    void clear();
}
