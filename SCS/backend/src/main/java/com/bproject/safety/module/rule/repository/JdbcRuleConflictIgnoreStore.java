package com.bproject.safety.module.rule.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** server profile：忽略决策落 openGauss safety.rule_conflict_ignore（NOT EXISTS 幂等）。 */
@Component
@Profile("server")
public class JdbcRuleConflictIgnoreStore implements RuleConflictIgnoreStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRuleConflictIgnoreStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean ignore(String ruleA, String ruleB, String param, String operator) {
        int updated = jdbc.update(
                "INSERT INTO safety.rule_conflict_ignore (rule_a, rule_b, param, ignored_by) "
                        + "SELECT :ruleA, :ruleB, :param, :operator "
                        + "WHERE NOT EXISTS (SELECT 1 FROM safety.rule_conflict_ignore "
                        + "WHERE rule_a=:ruleA AND rule_b=:ruleB AND param=:param)",
                Map.of("ruleA", ruleA, "ruleB", ruleB, "param", param,
                        "operator", operator == null ? "" : operator));
        return updated > 0;
    }

    @Override
    public List<IgnoredConflict> list() {
        var rows = jdbc.queryForList(
                "SELECT rule_a, rule_b, param, ignored_by, ignored_at FROM safety.rule_conflict_ignore "
                        + "ORDER BY ignored_at DESC",
                Map.of());
        return rows.stream().map(row -> new IgnoredConflict(
                String.valueOf(row.get("rule_a")),
                String.valueOf(row.get("rule_b")),
                String.valueOf(row.get("param")),
                row.get("ignored_by") == null ? "" : String.valueOf(row.get("ignored_by")),
                row.get("ignored_at") == null ? "" : String.valueOf(row.get("ignored_at")))).toList();
    }

    @Override
    public boolean isIgnored(String ruleA, String ruleB, String param) {
        var rows = jdbc.queryForList(
                "SELECT 1 FROM safety.rule_conflict_ignore WHERE rule_a=:ruleA AND rule_b=:ruleB AND param=:param",
                Map.of("ruleA", ruleA, "ruleB", ruleB, "param", param));
        return !rows.isEmpty();
    }
}
