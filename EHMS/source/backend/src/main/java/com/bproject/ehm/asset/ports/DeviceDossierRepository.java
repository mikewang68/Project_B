package com.bproject.ehm.asset.ports;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import java.util.Optional;
public interface DeviceDossierRepository {
    Optional<DeviceDossier> find(String assetCode);
    DeviceDossier save(DeviceDossier dossier,long expectedVersion);
}
