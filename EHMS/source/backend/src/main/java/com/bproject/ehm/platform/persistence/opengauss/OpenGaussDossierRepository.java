package com.bproject.ehm.platform.persistence.opengauss;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import com.bproject.ehm.asset.ports.DeviceDossierRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository @Profile("server")
public class OpenGaussDossierRepository implements DeviceDossierRepository {
    private final OpenGaussJsonStore store;
    public OpenGaussDossierRepository(OpenGaussJsonStore store){this.store=store;}
    public Optional<DeviceDossier> find(String code){return store.find("asset-dossier",code,DeviceDossier.class);}
    public DeviceDossier save(DeviceDossier value,long expected){return store.saveVersioned("asset-dossier",value.assetCode(),value,expected);}
}
