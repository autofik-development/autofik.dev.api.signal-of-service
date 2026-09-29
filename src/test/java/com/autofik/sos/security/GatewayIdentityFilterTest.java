package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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

  /** A fully verified block naming {@code uuid}, as the gateway sends it. */
  private static String verified(String key, String uuid) {
    return "{\""
        + key
        + "\":{\"uuid\":\""
        + uuid
        + "\",\"otpVerify\":true,\"profileVerify\":true"
        + (key.equals("user") ? ",\"role\":\"USER\"" : "")
        + "}}";
  }

  private Authentication authenticateWith(String userId, String mechanicId) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (userId != null)
      request.addHeader(AppHeaderContext.USER_CONTEXT_HEADER, verified("user", userId));
    if (mechanicId != null)
      request.addHeader(AppHeaderContext.MECHANIC_CONTEXT_HEADER, verified("mechanic", mechanicId));
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  void userContext_authenticatesAsDriver() throws Exception {
    UUID id = UUID.randomUUID();

    Authentication auth = authenticateWith(id.toString(), null);

    assertThat(auth.getPrincipal()).isEqualTo(id);
    assertThat(auth.getAuthorities())
        .extracting("authority")
        .containsExactly(GatewayIdentityFilter.ROLE_USER);
  }

  @Test
  void mechanicContext_authenticatesAsMechanic() throws Exception {
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

  // Two contexts naming different people means the edge is misconfigured. Guessing which one is
  // real is how an impersonation hole gets shipped.
  @Test
  void conflictingContexts_leaveRequestUnauthenticated() throws Exception {
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
    request.addHeader(
        AppHeaderContext.USER_CONTEXT_HEADER, verified("user", UUID.randomUUID().toString()));
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

  /**
   * An unusable header answers the same 401 as no header, so the log line is the only way to tell
   * the two apart. It must name the header and never echo its value, which names the user.
   */
  @Test
  void unusableContext_logsWhichHeaderArrivedButNotItsValue() throws Exception {
    List<String> logged = captureWarnings(() -> filterWithContext("{not json"));

    assertThat(logged)
        .singleElement()
        .asString()
        .contains("user-context")
        .doesNotContain("not json");
  }

  // The gateway never sends these; they must not identify anyone, and must stay silent.
  @Test
  void legacyHeaders_noLongerAuthenticate() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-User-Id", UUID.randomUUID().toString());
    request.addHeader("X-Mechanic-Id", UUID.randomUUID().toString());

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void noContext_logsNothing() throws Exception {
    List<String> logged =
        captureWarnings(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), chain));

    assertThat(logged).isEmpty();
  }

  private interface Action {
    void run() throws Exception;
  }

  private static List<String> captureWarnings(Action action) throws Exception {
    List<String> messages = new CopyOnWriteArrayList<>();
    AbstractAppender appender =
        new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            if (event.getLevel() == Level.WARN) {
              messages.add(event.getMessage().getFormattedMessage());
            }
          }
        };
    appender.start();
    Logger logger = (Logger) LogManager.getLogger(GatewayIdentityFilter.class);
    // Without Spring, log4j2-spring.xml never loads and Log4j's default config drops WARN.
    Level previous = logger.getLevel();
    logger.setLevel(Level.WARN);
    logger.addAppender(appender);
    try {
      action.run();
    } finally {
      logger.removeAppender(appender);
      logger.setLevel(previous);
    }
    return messages;
  }
}
