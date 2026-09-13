package com.bproject.ehm.monitoring.adapter.out.mongo;

import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;
import com.bproject.ehm.monitoring.domain.model.QualityStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("point_quality_snapshots")
@CompoundIndex(name = "idx_quality_asset_status", def = "{'assetCode': 1, 'status': 1}")
public class PointQualityDocument {
    @Id private final String pointCode;
    private final String assetCode;
    private final QualityStatus status;
    private final Double value;
    private final Instant sourceTimestamp;
    private final Instant receivedAt;
    private final Instant assessedAt;
    private final String message;
    private final int consecutiveFailures;
    @Version private final Long version;

    public PointQualityDocument(String pointCode, String assetCode, QualityStatus status, Double value,
                                Instant sourceTimestamp, Instant receivedAt, Instant assessedAt, String message,
                                int consecutiveFailures, Long version) {
        this.pointCode = pointCode;
        this.assetCode = assetCode;
        this.status = status;
        this.value = value;
        this.sourceTimestamp = sourceTimestamp;
        this.receivedAt = receivedAt;
        this.assessedAt = assessedAt;
        this.message = message;
        this.consecutiveFailures = consecutiveFailures;
        this.version = version;
    }

    static PointQualityDocument fromDomain(PointQualitySnapshot snapshot) {
        return new PointQualityDocument(snapshot.pointCode(), snapshot.assetCode(), snapshot.status(),
                snapshot.value(), snapshot.sourceTimestamp(), snapshot.receivedAt(), snapshot.assessedAt(),
                snapshot.message(), snapshot.consecutiveFailures(), snapshot.version());
    }

    PointQualitySnapshot toDomain() {
        return new PointQualitySnapshot(pointCode, assetCode, status, value, sourceTimestamp, receivedAt,
                assessedAt, message, consecutiveFailures, version);
    }
}
