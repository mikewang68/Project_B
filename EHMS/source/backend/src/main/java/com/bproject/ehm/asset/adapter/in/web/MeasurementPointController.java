package com.bproject.ehm.asset.adapter.in.web;

import com.bproject.ehm.asset.application.MeasurementPointApplicationService;
import com.bproject.ehm.asset.application.MeasurementPointCommand;
import com.bproject.ehm.asset.application.MeasurementPointView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ehm/v1")
public class MeasurementPointController {
    private final MeasurementPointApplicationService points;

    public MeasurementPointController(MeasurementPointApplicationService points) {
        this.points = points;
    }

    @GetMapping("/assets/{assetCode}/measurement-points")
    public PageResult<MeasurementPointView> list(@PathVariable String assetCode,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size,
                                                  @RequestParam(required = false) String keyword) {
        return points.list(assetCode, new PageQuery(page, size), keyword);
    }

    @GetMapping("/measurement-points/{code}")
    public MeasurementPointView get(@PathVariable String code) {
        return points.get(code);
    }

    @PostMapping("/assets/{assetCode}/measurement-points")
    @ResponseStatus(HttpStatus.CREATED)
    public MeasurementPointView create(@PathVariable String assetCode,
                                       @Valid @RequestBody MeasurementPointRequest request) {
        return points.create(assetCode, request.toCommand());
    }

    @PutMapping("/measurement-points/{code}")
    public MeasurementPointView update(@PathVariable String code,
                                       @Valid @RequestBody MeasurementPointRequest request) {
        return points.update(code, request.toCommand());
    }

    @DeleteMapping("/measurement-points/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String code) {
        points.archive(code);
    }

    public record MeasurementPointRequest(
            String code,
            @NotBlank(message = "所属部件不能为空") String componentCode,
            @NotBlank(message = "测点名称不能为空") String name,
            String metric,
            String unit,
            String sourceProtocol,
            String sourceAddress,
            @Positive(message = "采样周期必须为正数") Integer sampleIntervalSeconds,
            Double lowerLimit,
            Double upperLimit,
            Boolean enabled
    ) {
        MeasurementPointCommand toCommand() {
            return new MeasurementPointCommand(code, componentCode, name, metric, unit, sourceProtocol,
                    sourceAddress, sampleIntervalSeconds, lowerLimit, upperLimit, enabled);
        }
    }
}
