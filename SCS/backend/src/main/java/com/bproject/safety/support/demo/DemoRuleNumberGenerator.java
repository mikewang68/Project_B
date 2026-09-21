package com.bproject.safety.support.demo;

import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.module.rule.service.RuleNumberGenerator;
import org.springframework.stereotype.Component;

/**
 * Demo 规则编号生成器：{@code RULE-<域前缀>-NNN}，NNN 为同前缀规则数 + 1。
 * 单实例口径，<b>不保证多实例唯一</b>；前缀映射与历史 Demo 完全一致。
 */
@Component
public class DemoRuleNumberGenerator implements RuleNumberGenerator {

    private final RuleRepository repository;

    public DemoRuleNumberGenerator(RuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public synchronized String nextRuleNumber(String category) {
        String prefix = switch (category == null ? "人员安全" : category) {
            case "设备安全" -> "RULE-DEV";
            case "AI识别" -> "RULE-AI";
            case "告警策略" -> "RULE-ALM";
            case "联动策略" -> "RULE-LNK";
            case "通知策略" -> "RULE-NTF";
            default -> "RULE-PER";
        };
        String fullPrefix = prefix + "-";
        long maxSeq = 0;
        for (var r : repository.findAll()) {
            if (r.id != null && r.id.startsWith(fullPrefix)) {
                String suffix = r.id.substring(fullPrefix.length());
                try {
                    long seq = Long.parseLong(suffix);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略不符合规范的后缀
                }
            }
        }
        long next = maxSeq + 1;
        String candidate = String.format("%s%03d", fullPrefix, next);
        while (repository.findById(candidate).isPresent()) {
            next++;
            candidate = String.format("%s%03d", fullPrefix, next);
        }
        return candidate;
    }
}
