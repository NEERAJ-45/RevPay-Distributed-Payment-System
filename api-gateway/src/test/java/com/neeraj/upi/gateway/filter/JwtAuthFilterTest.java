package com.neeraj.upi.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = JwtAuthFilter.class)
public class JwtAuthFilterTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @Test
    public void testGatewayRoutingFilter() {
        webTestClient.get().uri("/wallet/balance/test")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
