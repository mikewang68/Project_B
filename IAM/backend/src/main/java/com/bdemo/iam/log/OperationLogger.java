package com.bdemo.iam.log;

import com.bdemo.iam.log.mapper.OperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 显式操作日志记录（简单可靠，不引入 AOP 框架）。写库失败不影响主业务。
 * 字段与 SYS 前端 SysLog 对齐：kind(login/operation)、module(auth/user/role)、
 * action(login/logout/add/edit/delete/...)、target、detail、result(success/fail)。
 */
@Service
public class OperationLogger {

    private static final Logger log = LoggerFactory.getLogger(OperationLogger.class);

    private final OperationLogMapper mapper;

    public OperationLogger(OperationLogMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String username, String userId, String kind, String module, String action,
                       String target, String detail, String method, String uri, String ip,
                       String result) {
        try {
            mapper.insert("l" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    kind, username, userId, module, action, target, detail,
                    method, uri, ip, normalize(result), null, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("操作日志写入失败（不影响主业务）: {}", e.getMessage());
        }
    }

    private String normalize(String result) {
        return "fail".equalsIgnoreCase(result) || "failed".equalsIgnoreCase(result) ? "fail" : "success";
    }
}
