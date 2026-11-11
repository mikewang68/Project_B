package com.bproject.safety.module.publicdata.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 非服务器 profile：与 db/v21/003 种子一致的静态绑定。 */
@Component
@Profile("!server")
public class InMemoryPublicDeviceBindings implements PublicDeviceBindings {

    private static final Map<String, Binding> SEED = Map.ofEntries(
            Map.entry("SIM-0ece1fee47f641be93d4ac53", new Binding("SIM-0ece1fee47f641be93d4ac53", "scs-radar", "RADAR", "VEH-07", true)),
            Map.entry("SIM-142e73df50a24723a70c4eae", new Binding("SIM-142e73df50a24723a70c4eae", "scs-aibox", "AI", "CAM-01", true)),
            Map.entry("SIM-14dcba2c643b48f084b55b7e", new Binding("SIM-14dcba2c643b48f084b55b7e", "scs-uwb", "UWB", "P-1001", true)),
            Map.entry("SIM-471ece2b2ec742939729173d", new Binding("SIM-471ece2b2ec742939729173d", "scs-tipper", "DEVICE", "TIP-02", true)),
            Map.entry("SIM-4bf65976b5754fd89d903232", new Binding("SIM-4bf65976b5754fd89d903232", "scs-limit-switch", "ZONE", "LOADING-A", true)),
            Map.entry("SIM-aed34d3f7bb84082a061bf7a", new Binding("SIM-aed34d3f7bb84082a061bf7a", "scs-crane", "DEVICE", "CRANE-01", true)),
            Map.entry("SIM-af1935235c6b4adbbfa7a2cc", new Binding("SIM-af1935235c6b4adbbfa7a2cc", "scs-alarm-light", "OBSERVE", "ALARM-LIGHT-01", true)),
            Map.entry("SIM-f0f11ffa302b424f80ab9d30", new Binding("SIM-f0f11ffa302b424f80ab9d30", "scs-position-sensor", "ZONE", "LOADING-B", true)));

    @Override
    public Optional<Binding> find(String deviceId) {
        return Optional.ofNullable(SEED.get(deviceId));
    }

    @Override
    public List<Binding> list() {
        return List.copyOf(SEED.values());
    }
}
