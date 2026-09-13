package com.bproject.ehm.reliability.adapter.out.mongo;

import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;
import com.bproject.ehm.reliability.ports.ReliabilityRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MongoReliabilityRepositoryAdapter implements ReliabilityRepository {
    private final FailureModeSpringRepository failureModes;
    private final AlarmRuleSpringRepository alarmRules;
    private final KnowledgeCaseSpringRepository cases;
    private final SlaPolicySpringRepository slaPolicies;

    public MongoReliabilityRepositoryAdapter(FailureModeSpringRepository failureModes,
                                             AlarmRuleSpringRepository alarmRules,
                                             KnowledgeCaseSpringRepository cases,
                                             SlaPolicySpringRepository slaPolicies) {
        this.failureModes = failureModes; this.alarmRules = alarmRules;
        this.cases = cases; this.slaPolicies = slaPolicies;
    }

    public List<FailureMode> failureModes() { return failureModes.findAllByOrderByRpnDesc().stream().map(FailureModeDocument::toDomain).toList(); }
    public Optional<FailureMode> failureMode(String id) { return failureModes.findById(id).map(FailureModeDocument::toDomain); }
    public FailureMode save(FailureMode value) { return failureModes.save(FailureModeDocument.from(value)).toDomain(); }
    public List<AlarmRule> alarmRules() { return alarmRules.findAllByOrderByUpdatedAtDesc().stream().map(AlarmRuleDocument::toDomain).toList(); }
    public Optional<AlarmRule> alarmRule(String code) { return alarmRules.findById(code).map(AlarmRuleDocument::toDomain); }
    public AlarmRule save(AlarmRule value) { return alarmRules.save(AlarmRuleDocument.from(value)).toDomain(); }
    public List<KnowledgeCase> knowledgeCases() { return cases.findAllByOrderByUpdatedAtDesc().stream().map(KnowledgeCaseDocument::toDomain).toList(); }
    public Optional<KnowledgeCase> knowledgeCase(String caseNo) { return cases.findById(caseNo).map(KnowledgeCaseDocument::toDomain); }
    public KnowledgeCase save(KnowledgeCase value) { return cases.save(KnowledgeCaseDocument.from(value)).toDomain(); }
    public List<SlaPolicy> slaPolicies() { return slaPolicies.findAllByOrderBySeverityDesc().stream().map(SlaPolicyDocument::toDomain).toList(); }
    public Optional<SlaPolicy> slaPolicy(String severity) { return slaPolicies.findById(severity).map(SlaPolicyDocument::toDomain); }
    public SlaPolicy save(SlaPolicy value) { return slaPolicies.save(SlaPolicyDocument.from(value)).toDomain(); }
}
