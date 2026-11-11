package com.bproject.safety.module.publicdata.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** server profile：查 safety.public_device_binding，进程内缓存（事件低频，绑定变更可重启刷新）。 */
@Component
@Profile("server")
public class JdbcPublicDeviceBindings implements PublicDeviceBindings {

    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, Optional<Binding>> cache = new ConcurrentHashMap<>();

    public JdbcPublicDeviceBindings(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Binding> find(String deviceId) {
        return cache.computeIfAbsent(deviceId, this::query);
    }

    @Override
    public List<Binding> list() {
        var rows = jdbc.queryForList(
                "SELECT device_id, template_id, scs_domain, scs_object_code, enabled FROM safety.public_device_binding ORDER BY device_id",
                Map.of());
        return rows.stream().map(row -> new Binding(
                String.valueOf(row.get("device_id")),
                String.valueOf(row.get("template_id")),
                String.valueOf(row.get("scs_domain")),
                String.valueOf(row.get("scs_object_code")),
                Boolean.TRUE.equals(row.get("enabled")))).toList();
    }

    private Optional<Binding> query(String deviceId) {
        var rows = jdbc.queryForList(
                "SELECT device_id, template_id, scs_domain, scs_object_code, enabled FROM safety.public_device_binding "
                        + "WHERE device_id=:deviceId AND enabled=true",
                Map.of("deviceId", deviceId));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.get(0);
        return Optional.of(new Binding(deviceId,
                String.valueOf(row.get("template_id")),
                String.valueOf(row.get("scs_domain")),
                String.valueOf(row.get("scs_object_code")),
                Boolean.TRUE.equals(row.get("enabled"))));
    }
}
