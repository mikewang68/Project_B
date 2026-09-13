package com.bproject.ehm.reliability.application;

import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;
import com.bproject.ehm.reliability.ports.ReliabilityRepository;
import com.bproject.ehm.shared.error.DomainConflictException;
import com.bproject.ehm.shared.error.ResourceNotFoundException;
import com.bproject.ehm.shared.error.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ReliabilityGovernanceApplicationService {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private final ReliabilityRepository repository;
    private final Clock clock;

    @Autowired
    public ReliabilityGovernanceApplicationService(ReliabilityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ReliabilityGovernanceApplicationService(ReliabilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<FailureMode> failureModes() { return repository.failureModes(); }
    public List<AlarmRule> alarmRules() { return repository.alarmRules(); }
    public List<KnowledgeCase> knowledgeCases() { return repository.knowledgeCases(); }
    public List<SlaPolicy> slaPolicies() { return repository.slaPolicies(); }

    public FailureMode createFailureMode(String faultCode, String assetType, String component,
                                         String name, String effect, String cause, int severity,
                                         int occurrence, int detectability, String control,
                                         String action, String owner) {
        try {
            if (repository.failureModes().stream().anyMatch(item -> item.faultCode().equalsIgnoreCase(faultCode))) {
                throw new DomainConflictException("故障编码已存在：" + faultCode);
            }
            Instant now = clock.instant();
            return repository.save(FailureMode.create("FM-" + DAY.format(now) + "-" + shortId(),
                    faultCode, assetType, component, name, effect, cause, severity, occurrence,
                    detectability, control, action, owner, now));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public FailureMode reviseFailureMode(String id, String assetType, String component,
                                         String name, String effect, String cause, int severity,
                                         int occurrence, int detectability, String control,
                                         String action, String owner) {
        try {
            FailureMode current = repository.failureMode(id)
                    .orElseThrow(() -> new ResourceNotFoundException("未找到失效模式：" + id));
            return repository.save(current.revise(assetType, component, name, effect, cause,
                    severity, occurrence, detectability, control, action, owner, clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public AlarmRule createAlarmRule(String code, String name, String assetType, String metric,
                                     String condition, String recovery, String severity,
                                     int persistenceSeconds, String owner) {
        try {
            String normalized = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
            if (normalized != null && repository.alarmRule(normalized).isPresent()) {
                throw new DomainConflictException("规则编码已存在：" + normalized);
            }
            return repository.save(AlarmRule.create(normalized, name, assetType, metric, condition,
                    recovery, severity, persistenceSeconds, owner, clock.instant()));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public AlarmRule publishRule(String code, String operator) {
        return repository.save(requireRule(code).publish(operator, clock.instant()));
    }

    public AlarmRule disableRule(String code, String reason) {
        return repository.save(requireRule(code).disable(reason, clock.instant()));
    }

    public KnowledgeCase createKnowledgeCase(String faultCode, String assetType, String component,
                                             String title, String symptom, String cause,
                                             List<String> steps, String remedy, String criterion,
                                             String sourceWorkOrderNo) {
        try {
            Instant now = clock.instant();
            return repository.save(KnowledgeCase.create("KB-" + DAY.format(now) + "-" + shortId(),
                    faultCode, assetType, component, title, symptom, cause, steps, remedy,
                    criterion, sourceWorkOrderNo, now));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    public KnowledgeCase verifyCase(String caseNo, String verifier) {
        KnowledgeCase current = repository.knowledgeCase(caseNo)
                .orElseThrow(() -> new ResourceNotFoundException("未找到知识案例：" + caseNo));
        return repository.save(current.verify(verifier, clock.instant()));
    }

    public SlaPolicy saveSla(String severity, int acknowledgeMinutes, int assignMinutes,
                             int recoverMinutes, String escalationRole) {
        try {
            Instant now = clock.instant();
            SlaPolicy created = SlaPolicy.create(severity, acknowledgeMinutes, assignMinutes,
                    recoverMinutes, escalationRole, now);
            SlaPolicy current = repository.slaPolicy(created.severity()).orElse(null);
            if (current != null) {
                created = new SlaPolicy(created.severity(), created.acknowledgeMinutes(),
                        created.assignMinutes(), created.recoverMinutes(), created.escalationRole(),
                        true, now, current.version());
            }
            return repository.save(created);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }

    private AlarmRule requireRule(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return repository.alarmRule(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("未找到告警规则：" + code));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
