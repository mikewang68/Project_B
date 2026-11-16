package com.bproject.safety.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** 派生告警授权只挂在当前请求上；不依赖 POSIX 文件权限，Windows 开发机也执行。 */
class ScsDerivedAuthTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void derivedAuthIsRequestScopedAndLimitedToExplicitWriteRoots() {
        var request = new MockHttpServletRequest("POST", "/api/v1/ingest/radar");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertNull(ScsCentralScope.derivedMachineAuth());
        ScsCentralScope.bindDerivedMachineAuth(
                ScsCentralScope.Root.COLLISION, "VEH-07", ScsCentralScope.Root.ALERT);

        var derived = ScsCentralScope.derivedMachineAuth();
        assertNotNull(derived);
        assertEquals(ScsCentralScope.Root.COLLISION, derived.ownerRoot());
        assertEquals("VEH-07", derived.ownerKey());
        assertTrue(derived.writeRoots().contains(ScsCentralScope.Root.ALERT));
        assertFalse(derived.writeRoots().contains(ScsCentralScope.Root.RULE));

        ScsCentralScope.clearDerivedMachineAuth();
        assertNull(ScsCentralScope.derivedMachineAuth());
    }

    @Test
    void machineWriteGateRequiresOwnedObjectOrDerivedAlertRoot() {
        var binding = org.mockito.Mockito.mock(ScsMachineRegistry.Binding.class);
        org.mockito.Mockito.when(binding.owns(ScsCentralScope.Root.COLLISION, "VEH-07")).thenReturn(true);
        org.mockito.Mockito.when(binding.owns(ScsCentralScope.Root.COLLISION, "OTHER")).thenReturn(false);
        var derived = new ScsCentralScope.DerivedAuth(
                ScsCentralScope.Root.COLLISION, "VEH-07", java.util.Set.of(ScsCentralScope.Root.ALERT));

        assertTrue(ScsCentralScope.machineMayWrite(binding, ScsCentralScope.Root.COLLISION,
                ScsCentralScope.Root.COLLISION, "VEH-07", null));
        assertFalse(ScsCentralScope.machineMayWrite(binding, ScsCentralScope.Root.ALERT,
                ScsCentralScope.Root.ALERT, "ALM-1", null));
        assertTrue(ScsCentralScope.machineMayWrite(binding, ScsCentralScope.Root.ALERT,
                ScsCentralScope.Root.ALERT, "ALM-1", derived));
        assertFalse(ScsCentralScope.machineMayWrite(binding, ScsCentralScope.Root.RULE,
                ScsCentralScope.Root.RULE, "RULE-1", derived));

        var foreign = new ScsCentralScope.DerivedAuth(
                ScsCentralScope.Root.COLLISION, "OTHER", java.util.Set.of(ScsCentralScope.Root.ALERT));
        assertFalse(ScsCentralScope.machineMayWrite(binding, ScsCentralScope.Root.ALERT,
                ScsCentralScope.Root.ALERT, "ALM-1", foreign));
        assertTrue(ScsCentralScope.machineMayWrite(null, ScsCentralScope.Root.ALERT,
                ScsCentralScope.Root.ALERT, "ALM-1", null));
    }
}
