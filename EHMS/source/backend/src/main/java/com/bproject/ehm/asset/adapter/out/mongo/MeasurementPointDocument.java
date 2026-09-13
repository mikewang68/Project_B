package com.bproject.ehm.asset.adapter.out.mongo;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("measurement_points")
@CompoundIndex(name = "idx_point_asset_active", def = "{'assetCode': 1, 'archived': 1, 'enabled': 1}")
public class MeasurementPointDocument {
    @Id private final String code;
    private final String assetCode;
    private final String componentCode;
    private final String name;
    private final String metric;
    private final String unit;
    private final String sourceProtocol;
    private final String sourceAddress;
    private final int sampleIntervalSeconds;
    private final Double lowerLimit;
    private final Double upperLimit;
    private final boolean enabled;
    private final boolean archived;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public MeasurementPointDocument(String code, String assetCode, String componentCode, String name, String metric,
                                    String unit, String sourceProtocol, String sourceAddress, int sampleIntervalSeconds,
                                    Double lowerLimit, Double upperLimit, boolean enabled, boolean archived,
                                    Instant createdAt, Instant updatedAt, Long version) {
        this.code = code;
        this.assetCode = assetCode;
        this.componentCode = componentCode;
        this.name = name;
        this.metric = metric;
        this.unit = unit;
        this.sourceProtocol = sourceProtocol;
        this.sourceAddress = sourceAddress;
        this.sampleIntervalSeconds = sampleIntervalSeconds;
        this.lowerLimit = lowerLimit;
        this.upperLimit = upperLimit;
        this.enabled = enabled;
        this.archived = archived;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static MeasurementPointDocument fromDomain(MeasurementPoint point) {
        return new MeasurementPointDocument(point.code(), point.assetCode(), point.componentCode(), point.name(),
                point.metric(), point.unit(), point.sourceProtocol(), point.sourceAddress(),
                point.sampleIntervalSeconds(), point.lowerLimit(), point.upperLimit(), point.enabled(),
                point.archived(), point.createdAt(), point.updatedAt(), point.version());
    }

    MeasurementPoint toDomain() {
        return new MeasurementPoint(code, assetCode, componentCode, name, metric, unit, sourceProtocol,
                sourceAddress, sampleIntervalSeconds, lowerLimit, upperLimit, enabled, archived,
                createdAt, updatedAt, version);
    }
}
