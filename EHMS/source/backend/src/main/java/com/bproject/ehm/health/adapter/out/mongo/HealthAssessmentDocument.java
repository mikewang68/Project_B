package com.bproject.ehm.health.adapter.out.mongo;

import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.health.domain.model.HealthFactor;
import com.bproject.ehm.health.domain.model.PredictionReview;
import com.bproject.ehm.health.domain.model.RulPrediction;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("health_assessments")
public class HealthAssessmentDocument {
    @Id private final String assessmentId;
    @Indexed private final String assetCode;
    private final String assetName;
    private final Integer healthScore;
    private final String healthGrade;
    private final double confidencePercent;
    private final String status;
    private final String method;
    private final String modelVersion;
    private final String featureVersion;
    private final double dataAvailabilityPercent;
    private final List<HealthFactor> factors;
    private final RulPrediction prediction;
    private final List<String> limitations;
    private final Instant inputWindowStart;
    private final Instant inputWindowEnd;
    @Indexed private final Instant generatedAt;
    private final Instant validUntil;
    private final PredictionReview review;
    @Indexed(sparse = true) private final String workOrderNo;
    @Version private final Long version;

    public HealthAssessmentDocument(String assessmentId, String assetCode, String assetName,
                                    Integer healthScore, String healthGrade, double confidencePercent,
                                    String status, String method, String modelVersion, String featureVersion,
                                    double dataAvailabilityPercent, List<HealthFactor> factors,
                                    RulPrediction prediction, List<String> limitations,
                                    Instant inputWindowStart, Instant inputWindowEnd, Instant generatedAt,
                                    Instant validUntil, PredictionReview review, String workOrderNo, Long version) {
        this.assessmentId = assessmentId;
        this.assetCode = assetCode;
        this.assetName = assetName;
        this.healthScore = healthScore;
        this.healthGrade = healthGrade;
        this.confidencePercent = confidencePercent;
        this.status = status;
        this.method = method;
        this.modelVersion = modelVersion;
        this.featureVersion = featureVersion;
        this.dataAvailabilityPercent = dataAvailabilityPercent;
        this.factors = factors;
        this.prediction = prediction;
        this.limitations = limitations;
        this.inputWindowStart = inputWindowStart;
        this.inputWindowEnd = inputWindowEnd;
        this.generatedAt = generatedAt;
        this.validUntil = validUntil;
        this.review = review;
        this.workOrderNo = workOrderNo;
        this.version = version;
    }

    static HealthAssessmentDocument fromDomain(HealthAssessment value) {
        return new HealthAssessmentDocument(value.assessmentId(), value.assetCode(), value.assetName(),
                value.healthScore(), value.healthGrade(), value.confidencePercent(), value.status(),
                value.method(), value.modelVersion(), value.featureVersion(), value.dataAvailabilityPercent(),
                value.factors(), value.prediction(), value.limitations(), value.inputWindowStart(),
                value.inputWindowEnd(), value.generatedAt(), value.validUntil(), value.review(),
                value.workOrderNo(), value.version());
    }

    HealthAssessment toDomain() {
        return new HealthAssessment(assessmentId, assetCode, assetName, healthScore, healthGrade,
                confidencePercent, status, method, modelVersion, featureVersion, dataAvailabilityPercent,
                factors, prediction, limitations, inputWindowStart, inputWindowEnd, generatedAt,
                validUntil, review, workOrderNo, version);
    }
}
