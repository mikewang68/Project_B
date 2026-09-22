package com.bdemo.scheduling;

import com.bdemo.energy.agent.AgentService;
import com.bdemo.energy.pipeline.PipelineService;
import java.time.LocalDateTime;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="b-demo.scheduling-enabled", havingValue="true")
@Component public class EnergyScheduler{
 @org.springframework.beans.factory.annotation.Value("${b-demo.demo-now}") private LocalDateTime now;
 private static final Logger log=LoggerFactory.getLogger(EnergyScheduler.class);private final AgentService agent;private final PipelineService pipeline;public EnergyScheduler(AgentService a,PipelineService p){agent=a;pipeline=p;}
 // REQ-015/016/020/039: scheduling is owned by Spring; seeded aggregates remain immutable when no new source batch exists.
 @Scheduled(zone="Asia/Shanghai",cron="0 */15 * * * *") public void aggregateAndRules(){LocalDateTime end=now;var counts=pipeline.rebuildRange(end.minusHours(2),end);log.info("energy aggregate tick: {}",counts);pipeline.ruleStatus();}
 // REQ-051~056: daily cost orchestration uses the immutable version workflow exposed by CostService.
 @Scheduled(zone="Asia/Shanghai",cron="0 0 3 * * *") public void dailyCost(){log.info("daily energy cost integrity: {}",pipeline.costIntegrity());}
 @Scheduled(zone="Asia/Shanghai",cron="0 0 7 * * *") public void inspection(){agent.run("scheduled");}
}
