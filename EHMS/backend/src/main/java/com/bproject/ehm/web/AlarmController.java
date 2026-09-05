package com.bproject.ehm.web;

import com.bproject.ehm.domain.Alarm;
import com.bproject.ehm.repository.AlarmRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1/alarms")
public class AlarmController {
    private final AlarmRepository repository;

    public AlarmController(AlarmRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Alarm> list() {
        return repository.findAllByOrderByOccurredAtDesc();
    }

    @PostMapping("/{alarmNo}/acknowledge")
    public Alarm acknowledge(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request) {
        Alarm alarm = repository.findById(alarmNo).orElseThrow(() -> notFound(alarmNo));
        if ("已关闭".equals(alarm.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已关闭告警不能再次确认");
        }
        return repository.save(alarm.acknowledge(operator(request)));
    }

    @PostMapping("/{alarmNo}/close")
    public Alarm close(@PathVariable String alarmNo, @RequestBody(required = false) OperatorRequest request) {
        Alarm alarm = repository.findById(alarmNo).orElseThrow(() -> notFound(alarmNo));
        return repository.save(alarm.close(operator(request)));
    }

    private String operator(OperatorRequest request) {
        return request == null || request.operator() == null || request.operator().isBlank()
                ? "Demo设备管理员" : request.operator();
    }

    private ResponseStatusException notFound(String alarmNo) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到告警：" + alarmNo);
    }

    public record OperatorRequest(String operator) {
    }
}
