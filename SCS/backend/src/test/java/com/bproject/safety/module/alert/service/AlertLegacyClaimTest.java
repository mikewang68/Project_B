package com.bproject.safety.module.alert.service;

import com.bproject.iam.context.ScopeTriple;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 命名来源接管时，旧共享 key 留下的无范围戳防碰撞告警应被认领后复用，而不是重复建单。 */
class AlertLegacyClaimTest {

    private static final ScopeTriple TRIPLE = new ScopeTriple("PROJECT_B", "SHEN_TIE", "SCS");
    private static final String DEDUP = "COLLISION:VEH-07:TIP-02";

    @Test
    void claimsLegacyOpenCollisionAlertAndReusesIt() {
        var repository = mock(AlertRepository.class);
        var legacy = alert("ALM-OLD");
        when(repository.findOpenByDedupKey(DEDUP)).thenReturn(Optional.empty());
        when(repository.findOpenLegacyCollisionAlertNo(DEDUP)).thenReturn(Optional.of("ALM-OLD"));
        when(repository.claimLegacyCollisionAlert("ALM-OLD", TRIPLE)).thenReturn(1);
        when(repository.findById("ALM-OLD")).thenReturn(Optional.of(legacy));

        var service = service(repository);
        var result = service.findOpenByDedupKeyForMachine(DEDUP, TRIPLE);

        assertEquals("ALM-OLD", result.id);
        verify(repository).claimLegacyCollisionAlert("ALM-OLD", TRIPLE);
    }

    @Test
    void returnsVisibleScopedAlertWithoutTouchingLegacyRows() {
        var repository = mock(AlertRepository.class);
        when(repository.findOpenByDedupKey(DEDUP)).thenReturn(Optional.of(alert("ALM-SCOPED")));

        var result = service(repository).findOpenByDedupKeyForMachine(DEDUP, TRIPLE);

        assertEquals("ALM-SCOPED", result.id);
        verify(repository, never()).findOpenLegacyCollisionAlertNo(anyString());
        verify(repository, never()).claimLegacyCollisionAlert(anyString(), eq(TRIPLE));
    }

    @Test
    void returnsNullWhenClaimLosesRaceOrRowIsAlreadyClosed() {
        var repository = mock(AlertRepository.class);
        when(repository.findOpenByDedupKey(DEDUP)).thenReturn(Optional.empty());
        when(repository.findOpenLegacyCollisionAlertNo(DEDUP)).thenReturn(Optional.of("ALM-OLD"));
        when(repository.claimLegacyCollisionAlert("ALM-OLD", TRIPLE)).thenReturn(0);

        assertNull(service(repository).findOpenByDedupKeyForMachine(DEDUP, TRIPLE));
    }

    private AlertService service(AlertRepository repository) {
        return new AlertService(repository, null, null, null, Clock.systemUTC(),
                AlertChangeNotifier.NOOP, null);
    }

    private DemoAlert alert(String id) {
        var alert = new DemoAlert();
        alert.id = id;
        alert.dedupKey = DEDUP;
        alert.statusCode = "PENDING_CONFIRM";
        alert.riskCode = "SEVERE";
        return alert;
    }
}
