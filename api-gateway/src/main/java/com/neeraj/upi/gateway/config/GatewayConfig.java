package com.neeraj.upi.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Gateway configuration — rate limiter key resolver.
 *
 * The KeyResolver decides HOW to identify a "client" for rate limiting.
 * Current strategy: by remote IP address.
 * Future: can switch to user ID extracted from JWT for per-user limits.
 */
@Configuration
public class GatewayConfig {

    /**
     * Resolves the rate limiting key from the client's remote IP address.
     * Each IP gets its own rate limit bucket in Redis.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getHostString())
                .defaultIfEmpty("unknown");
    }
}
