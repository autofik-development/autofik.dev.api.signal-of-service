package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The filter is the only thing standing between a caller and acting as someone else, so each
 * rejection path is worth pinning: an unauthenticated context is what makes the chain answer 401.
 */
class GatewayIdentityFilterTest {

  // Jackson 3 (tools.jackson), which is what Boot 4 auto-configures and what the filter is
  // injected with — a Jackson 2 mapper here would pass while production had no such bean at all.
  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final GatewayIdentityFilter filter =
      new GatewayIdentityFilter(
          new MicroserviceTokenSdk(objectMapper),
          objectMapper,
          new AuthErrorResponder(objectMapper));
  private final FilterChain chain = mock(FilterChain.class);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private Authentication authenticateWith(String userHeader, String mechanicHeader)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (userHeader != null) request.addHeader(GatewayIdentityFilter.USER_HEADER, userHeader);
    if (mechanicHeader != null)
      request.addHeader(GatewayIdentityFilter.MECHANIC_HEADER, mechanicHeader);
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  void userHeader_authenticatesAsDriver() throws Exception {
    UUID id = UUID.randomUUID();

    Authentication auth = authenticateWith(id.toString(), null);

    assertThat(auth.getPrincipal()).isEqualTo(id);
    assertThat(auth.getAuthorities())
        .extracting("authority")
        .containsExactly(GatewayIdentityFilter.ROLE_USER);
  }

  @Test
  void mechanicHeader_authenticatesAsMechanic() throws Exception {
    UUID id = UUID.randomUUID();

    Authentication auth = authenticateWith(null, id.toString());

    assertThat(auth.getPrincipal()).isEqualTo(id);
    assertThat(auth.getAuthorities())
        .extracting("authority")
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
    assertThat(authenticateWith(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
        .isNull();
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

  /** Everything past the filter reads role and device from here rather than the headers. */
  @Test
  void carriesTheResolvedContextAsDetails() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        AppHeaderContext.USER_CONTEXT_HEADER,
        "{\"user\":{\"uuid\":\""
            + UUID.randomUUID()
            + "\",\"role\":\"MECHANIC\",\"deviceId\":\"pixel-8\",\"otpVerify\":true,"
            + "\"profileVerify\":true,\"tokenStatus\":\"active\",\"isLogout\":false}}");
    request.addHeader(AppHeaderContext.SERVICE_CODE_HEADER, "SOS");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth.getAuthorities())
        .extracting("authority")
        .containsExactly(GatewayIdentityFilter.ROLE_MECHANIC);
    assertThat(auth.getDetails())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(CurrentUser.class))
        .extracting(CurrentUser::deviceId, CurrentUser::serviceCode)
        .containsExactly("pixel-8", "SOS");
  }

  /**
   * A rejected context is answered here rather than passed down as anonymous: the chain's blanket
   * 401 cannot say whether to log in again or to go finish verification.
   */
  private MockHttpServletResponse filterWithContext(String userContextJson) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(AppHeaderContext.USER_CONTEXT_HEADER, userContextJson);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static String context(String extraFields) {
    return "{\"user\":{\"uuid\":\""
        + UUID.randomUUID()
        + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":true,"
        + extraFields
        + "}}";
  }

  @Test
  void loggedOutSession_answers401AndStopsTheChain() throws Exception {
    MockHttpServletResponse response = filterWithContext(context("\"isLogout\":true"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).contains("no longer valid");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain, never())
        .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unverifiedProfile_answers403AndStopsTheChain() throws Exception {
    MockHttpServletResponse response =
        filterWithContext(
            "{\"user\":{\"uuid\":\""
                + UUID.randomUUID()
                + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":false}}");

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("not fully verified");
    verify(chain, never())
        .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  // An absent context is not a rejection — health checks and the upgrade carry none.
  @Test
  void noContext_passesThroughAnonymous() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(request, response);
  }
}
