package com.bproject.ehm.reliability.ports;

import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;

import java.util.List;
import java.util.Optional;

public interface ReliabilityRepository {
    List<FailureMode> failureModes();
    Optional<FailureMode> failureMode(String id);
    FailureMode save(FailureMode value);

    List<AlarmRule> alarmRules();
    Optional<AlarmRule> alarmRule(String code);
    AlarmRule save(AlarmRule value);

    List<KnowledgeCase> knowledgeCases();
    Optional<KnowledgeCase> knowledgeCase(String caseNo);
    KnowledgeCase save(KnowledgeCase value);

    List<SlaPolicy> slaPolicies();
    Optional<SlaPolicy> slaPolicy(String severity);
    SlaPolicy save(SlaPolicy value);
}
