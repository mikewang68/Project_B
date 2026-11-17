package com.bproject.ehm.platform.persistence.opengauss;

import com.bproject.ehm.platform.audit.domain.model.AuditRecord;
import com.bproject.ehm.platform.audit.ports.AuditRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@Profile("server")
class OpenGaussAuditRepository extends OpenGaussAdapterSupport implements AuditRepository {
    private static final String TYPE="audit";
    OpenGaussAuditRepository(OpenGaussJsonStore store){super(store);}
    public AuditRecord append(AuditRecord value){return store.appendAudit(value);}
    public List<AuditRecord> latest(int limit){
        return store.recent(TYPE,null,null,"occurredAt",null,null,Math.max(1,Math.min(limit,200)),AuditRecord.class);
    }
}
