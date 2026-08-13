package com.autofik.sos.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The filter is the only thing standing between a caller and acting as someone else, so each
 * rejection path is worth pinning: an unauthenticated context is what makes the chain answer 401.
 */
class GatewayIdentityFilterTest {

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticateWith(String userHeader, String mechanicHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userHeader != null) request.addHeader(GatewayIdentityFilter.USER_HEADER, userHeader);
        if (mechanicHeader != null) request.addHeader(GatewayIdentityFilter.MECHANIC_HEADER, mechanicHeader);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void userHeader_authenticatesAsDriver() throws Exception {
        UUID id = UUID.randomUUID();

        Authentication auth = authenticateWith(id.toString(), null);

        assertThat(auth.getPrincipal()).isEqualTo(id);
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactly(GatewayIdentityFilter.ROLE_USER);
    }

    @Test
    void mechanicHeader_authenticatesAsMechanic() throws Exception {
        UUID id = UUID.randomUUID();

        Authentication auth = authenticateWith(null, id.toString());

        assertThat(auth.getPrincipal()).isEqualTo(id);
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactly(GatewayIdentityFilter.ROLE_MECHANIC);
    }

    @Test
    void noHeader_leavesRequestUnauthenticated() throws Exception {
        assertThat(authenticateWith(null, null)).isNull();
    }

    // Both headers means the edge is misconfigured. Guessing which one is real is how an
    // impersonation hole gets shipped.
    @Test
    void bothHeaders_leavesRequestUnauthenticated() throws Exception {
        assertThat(authenticateWith(UUID.randomUUID().toString(), UUID.randomUUID().toString())).isNull();
    }

    @Test
    void malformedId_leavesRequestUnauthenticated() throws Exception {
        assertThat(authenticateWith("not-a-uuid", null)).isNull();
    }

    // The bearer is kept for the SSO reads that are scoped to the calling user.
    @Test
    void carriesCallerBearerAsCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayIdentityFilter.USER_HEADER, UUID.randomUUID().toString());
        request.addHeader("Authorization", "Bearer abc123");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(Caller.token()).isEqualTo("Bearer abc123");
    }
}
