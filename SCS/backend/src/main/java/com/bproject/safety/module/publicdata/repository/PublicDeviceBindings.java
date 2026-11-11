package com.bproject.safety.module.publicdata.repository;

import java.util.List;
import java.util.Optional;

/** 公共模拟设备 → SCS 业务域绑定查找。 */
public interface PublicDeviceBindings {

    record Binding(String deviceId, String templateId, String domain, String objectCode, boolean enabled) {
    }

    Optional<Binding> find(String deviceId);

    List<Binding> list();
}
