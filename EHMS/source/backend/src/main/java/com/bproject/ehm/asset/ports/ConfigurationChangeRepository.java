package com.bproject.ehm.asset.ports;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import java.util.List;
import java.util.Optional;
public interface ConfigurationChangeRepository {
    Optional<ConfigurationChange> findByNo(String no);
    List<ConfigurationChange> findAll();
    ConfigurationChange save(ConfigurationChange value);
    long count();
}
