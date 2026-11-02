package com.bproject.ehm.simulation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Deployment-owner opt-in, never an account bootstrap and never overrides a saved pause. */
@Component @Profile("server")
@ConditionalOnProperty(name={"ehm.simulation.enabled","ehm.simulation.auto-install"},havingValue="true")
public class SimulationBootstrap {
    private final SimulationService service;private final boolean start;
    public SimulationBootstrap(SimulationService service,@Value("${ehm.simulation.auto-start:false}") boolean start){this.service=service;this.start=start;}
    @EventListener(ApplicationReadyEvent.class) public void ready(){
        var control=(SimulationService.Control)service.status().get("control");
        if(control.initialized())return;
        service.install(SimulationService.CONFIRMATION);
        if(start)service.running(true,SimulationService.CONFIRMATION);
    }
}
