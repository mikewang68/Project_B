package com.bproject.ehm.maintenance.application;
import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.ProjectContext;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.simulation.SimulationRunScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;

/** Optional demo-only reminders. Never enables the simulator or invents maintenance criteria. */
@Component @Profile("server")
@ConditionalOnProperty(name="ehm.maintenance.demo-reminders.enabled",havingValue="true")
public class DemoMaintenanceReminderJob {
    private final AssetQueryFacade assets;private final DeviceDossierService dossiers;
    private final MaintenanceReminderService reminders;private final OpenGaussJsonStore store;
    public DemoMaintenanceReminderJob(AssetQueryFacade assets,DeviceDossierService dossiers,MaintenanceReminderService reminders,OpenGaussJsonStore store){
        this.assets=assets;this.dossiers=dossiers;this.reminders=reminders;this.store=store;
    }
    @Scheduled(fixedDelayString="${ehm.maintenance.demo-reminders.delay-ms:900000}",initialDelayString="${ehm.maintenance.demo-reminders.initial-delay-ms:60000}")
    public void scan(){
        // bindDemo rejects request context; the job has no user session, no global query, and no write to SHEN_TIE/HA_TIE/UNASSIGNED.
        try(var project=ProjectContext.bind(SimulationRunScope.PROJECT);var scope=SimulationRunScope.bindDemo()){
            int checked=0,created=0,failed=0;boolean truncated=false;
            for(int n=0;n<25;n++){
                var batch=assets.list(new PageQuery(n,200),null,null,null);
                for(var device:batch.content()){
                    try{var basis=dossiers.get(device.code()).maintenanceBasis();
                        if(basis==null||!"SIMULATED".equals(basis.sourceKind()))continue;
                        checked++;if(Boolean.TRUE.equals(reminders.scan(device.code(),"ehms-demo-maintenance-reminders").get("created")))created++;
                    }catch(RuntimeException error){failed++;}
                }
                if(n+1>=batch.totalPages())break;if(n==24)truncated=true;
            }
            store.save("maintenance-reminder-job","demo-latest",Map.of("projectId",SimulationRunScope.PROJECT,"regionId",SimulationRunScope.REGION,
                "checked",checked,"created",created,"failed",failed,"truncated",truncated,"finishedAt",Instant.now()));
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("DEMO_MAINTENANCE_REMINDER_SCAN_NOT_COMPLETED; no scope fallback");}
    }
}
