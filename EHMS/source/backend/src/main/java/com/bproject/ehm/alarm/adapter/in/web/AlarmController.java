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

@RestController
@RequestMapping("/api/ehm/v1/alarms")
public class AlarmController {
    private final AlarmApplicationService alarms;

    public AlarmController(AlarmApplicationService alarms) {
        this.alarms = alarms;
    }

    @GetMapping
    public PageResult<AlarmView> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return alarms.list(new PageQuery(page, size));
    }

    @PostMapping("/{alarmNo}/acknowledge")
    public AlarmView acknowledge(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request) {
        return alarms.acknowledge(alarmNo, operator(request));
    }

    @PostMapping("/{alarmNo}/close")
    public AlarmView close(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request) {
        return alarms.close(alarmNo, operator(request), request == null ? null : request.reason());
    }

    private String operator(OperatorRequest request) {
        return request == null || request.operator() == null || request.operator().isBlank()
                ? "Demo设备管理员" : request.operator();
    }

    public record OperatorRequest(String operator, String reason) {
    }
}
