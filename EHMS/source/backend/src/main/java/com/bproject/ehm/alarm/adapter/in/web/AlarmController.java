package com.bproject.ehm.alarm.adapter.in.web;

import com.bproject.ehm.alarm.application.AlarmApplicationService;
import com.bproject.ehm.alarm.application.AlarmView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1/alarms")
public class AlarmController {
    private final AlarmApplicationService alarms;

    public AlarmController(AlarmApplicationService alarms) {
        this.alarms = alarms;
    }

    @GetMapping
    public PageResult<AlarmView> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size,
                                      @RequestParam(required=false) String keyword,@RequestParam(required=false) String level,
                                      @RequestParam(required=false) String status,@RequestParam(required=false) String category) {
        return alarms.search(new PageQuery(page, size),keyword,level,status,category);
    }

    @PostMapping("/{alarmNo}/acknowledge")
    public AlarmView acknowledge(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request, jakarta.servlet.http.HttpServletRequest http) {
        return alarms.acknowledge(alarmNo, com.bproject.ehm.platform.security.EhmOperationIdentity.require(http));
    }

    @PostMapping("/{alarmNo}/close")
    public AlarmView close(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request, jakarta.servlet.http.HttpServletRequest http) {
        return alarms.close(alarmNo, com.bproject.ehm.platform.security.EhmOperationIdentity.require(http), request == null ? null : request.reason());
    }

    @PostMapping("/batch-assign")
    public List<AlarmView> assignBatch(@RequestBody BatchAssignRequest request, jakarta.servlet.http.HttpServletRequest http) {
        return alarms.assignBatch(request.alarmNos(), request.assignee(), com.bproject.ehm.platform.security.EhmOperationIdentity.require(http), request.reason());
    }

    public record OperatorRequest(String operator, String reason) {
    }

    public record BatchAssignRequest(List<String> alarmNos, String assignee, String operator, String reason) {
    }
}
