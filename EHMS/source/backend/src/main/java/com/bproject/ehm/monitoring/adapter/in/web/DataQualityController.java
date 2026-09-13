package com.bproject.ehm.monitoring.adapter.in.web;

import com.bproject.ehm.monitoring.application.DataQualityApplicationService;
import com.bproject.ehm.monitoring.application.DataQualityPointView;
import com.bproject.ehm.monitoring.application.DataQualitySummary;
import com.bproject.ehm.monitoring.application.PointSampleCommand;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/ehm/v1/data-quality")
public class DataQualityController {
    private final DataQualityApplicationService dataQuality;

    public DataQualityController(DataQualityApplicationService dataQuality) {
        this.dataQuality = dataQuality;
    }

    @GetMapping("/points")
    public PageResult<DataQualityPointView> list(@RequestParam String assetCode,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size,
                                                  @RequestParam(required = false) String keyword) {
        return dataQuality.list(assetCode, new PageQuery(page, size), keyword);
    }

    @GetMapping("/summary")
    public DataQualitySummary summary(@RequestParam String assetCode) {
        return dataQuality.summary(assetCode);
    }

    @PostMapping("/points/{pointCode}/sample")
    public DataQualityPointView ingest(@PathVariable String pointCode, @Valid @RequestBody SampleRequest request) {
        return dataQuality.ingest(pointCode,
                new PointSampleCommand(request.value(), request.sourceTimestamp(), request.receivedAt()));
    }

    public record SampleRequest(
            @NotNull(message = "采样值不能为空") Double value,
            @NotNull(message = "源时间不能为空") Instant sourceTimestamp,
            Instant receivedAt
    ) {
    }
}
