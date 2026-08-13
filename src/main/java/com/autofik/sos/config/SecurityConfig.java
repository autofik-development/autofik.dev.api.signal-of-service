package com.autofik.sos.config;

import com.autofik.sos.security.GatewayIdentityFilter;
import lombok.RequiredArgsConstructor;
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
public class SecurityConfig {

    private final GatewayIdentityFilter gatewayIdentityFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // Health checks are read by the load balancer, which carries no identity.
                .requestMatchers("/", "/healthy", "/actuator/**", "/error").permitAll()
                // A browser cannot set headers on a WebSocket handshake, so the socket authenticates
                // on the STOMP CONNECT frame instead. See WebSocketConfig.
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayIdentityFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
