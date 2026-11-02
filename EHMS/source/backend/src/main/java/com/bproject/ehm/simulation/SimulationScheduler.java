package com.bproject.ehm.simulation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("server")
@ConditionalOnProperty(name="ehm.simulation.enabled",havingValue="true")
public class SimulationScheduler {
    private final SimulationService service;
    public SimulationScheduler(SimulationService service){this.service=service;}
    @Scheduled(fixedDelayString="${ehm.simulation.poll-ms:1000}",initialDelayString="${ehm.simulation.initial-delay-ms:15000}")
    public void poll(){
        try{service.scheduledTick();}
        catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Simulation paused after failed round: {}",e.getClass().getSimpleName());}
    }
}
