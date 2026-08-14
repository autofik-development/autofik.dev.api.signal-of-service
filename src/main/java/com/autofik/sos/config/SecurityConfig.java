package com.autofik.sos.config;

import com.autofik.sos.security.GatewayIdentityFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final GatewayIdentityFilter gatewayIdentityFilter;

    /**
     * Serves the WebSocket test console and lets the handshake fall back to {@code ?clientId=}.
     * See {@link WebSocketConfig} — that fallback lets a caller connect as anyone, so this is a
     * local-development switch and nothing else. Default false; set only in application-dev.yaml.
     */
    @Value("${websocket.dev-insecure-handshake:false}")
    private boolean devInsecureHandshake;

    @PostConstruct
    void warnIfInsecure() {
        if (devInsecureHandshake) {
            log.warn("websocket.dev-insecure-handshake=true — the WebSocket handshake accepts a "
                    + "caller-supplied ?clientId=, so any client can connect as any user. "
                    + "This must never be enabled outside local development.");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> {
                // Health checks are read by the load balancer, which carries no identity.
                auth.requestMatchers("/", "/healthy", "/actuator/**", "/error").permitAll();
                // The upgrade carries the identity headers and is authorised in WebSocketConfig's
                // handshake interceptor, which rejects it there rather than here.
                auth.requestMatchers("/ws/**").permitAll();
                if (devInsecureHandshake) {
                    auth.requestMatchers("/test-ws.html", "/OneSignalSDKWorker.js").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
