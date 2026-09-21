package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import java.util.List;

/**
 * 运维事件日志存储（独立于 Alert Timeline；不做 ELK，仅内存最近记录）。
 *
 * <p>Phase B：append-only 正式契约——只有 {@link #append(OpsEventLog)} 与查询，
 * 不提供 clear/delete；Demo / Test 清空走 {@code DemoClearableStore} 维护接口。</p>
 */
public interface OpsEventLogRepository {

    /** 追加一条不可变日志，返回存储后的副本。 */
    OpsEventLog append(OpsEventLog log);

    /** 最近日志（倒序，最新在前），支持 nodeId / level / type 过滤。 */
    List<OpsEventLog> recent(Integer limit, String nodeId, String level, String type,
                             String from, String to);
}
