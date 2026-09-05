package com.bdemo.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    @Test
    void createsAndParsesAuthenticatedUser() {
        JwtService service = new JwtService(
                "test-secret-with-at-least-thirty-two-bytes-long", 5);

        AuthenticatedUser user = service.parse(service.create(42, "energy_mgr"));

        assertThat(user.userId()).isEqualTo(42);
        assertThat(user.userName()).isEqualTo("energy_mgr");
    }
}
