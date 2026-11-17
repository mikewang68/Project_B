package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.health.ports.HealthAssessmentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("server")
class OpenGaussHealthAssessmentRepository extends OpenGaussAdapterSupport implements HealthAssessmentRepository {
    private static final String TYPE="health-assessment";
    OpenGaussHealthAssessmentRepository(OpenGaussJsonStore store){super(store);}
    public Optional<HealthAssessment> findById(String id){return store.find(TYPE,id,HealthAssessment.class);}
    public Optional<HealthAssessment> findLatestByAssetCode(String assetCode){
        return store.recent(TYPE,"assetCode",assetCode,"generatedAt",null,null,1,HealthAssessment.class).stream().findFirst();
    }
    public List<HealthAssessment> findRecentByAssetCode(String assetCode,int limit){
        return store.recent(TYPE,"assetCode",assetCode,"generatedAt",null,null,Math.max(1,Math.min(limit,100)),HealthAssessment.class);
    }
    public HealthAssessment save(HealthAssessment value){return store.save(TYPE,value.assessmentId(),value);}
}
