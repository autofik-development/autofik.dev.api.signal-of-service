package com.autofik.sos.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.autofik.sos.service.impl.MechanicProfileSync;
import com.autofik.sos.service.impl.UserProfileSync;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The filter hands an accepted caller's profile to the sync for their own role, and no other. */
class GatewayIdentityFilterProfileSyncTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private final MechanicProfileSync sync = mock(MechanicProfileSync.class);
  private final UserProfileSync userSync = mock(UserProfileSync.class);
  private final GatewayIdentityFilter filter =
      new GatewayIdentityFilter(
          new MicroserviceTokenSdk(objectMapper),
          objectMapper,
          new AuthErrorResponder(objectMapper));
  private final FilterChain chain = mock(FilterChain.class);

  GatewayIdentityFilterProfileSyncTest() {
    filter.profileSync = sync;
    filter.userProfileSync = userSync;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletResponse call(String header, String value) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(header, value);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static String mechanic(UUID id, String flags) {
    return "{\"mechanic\":{\"uuid\":\""
        + id
        + "\","
        + flags
        + "\"skillService\":{\"skillTag\":[\"tow\"],\"serviceRadiusKm\":20}}}";
  }

  @Test
  void anAcceptedMechanic_hasItsProfileSynced() throws Exception {
    UUID id = UUID.randomUUID();

    call("mechanic-context", mechanic(id, "\"otpVerify\":true,\"profileVerify\":true,"));

    verify(sync).sync(eq(id), any(MechanicContext.Mechanic.class));
    verify(chain).doFilter(any(), any());
  }

  @Test
  void aRejectedMechanic_isNotSynced() throws Exception {
    // A driver role inside mechanic-context is refused, so nothing is synced.
    call("mechanic-context", mechanic(UUID.randomUUID(), "\"role\":\"USER\","));

    verify(sync, never()).sync(any(), any());
  }

  private static String driver(UUID id) {
    return "{\"user\":{\"uuid\":\""
        + id
        + "\",\"role\":\"USER\",\"otpVerify\":true,\"profileVerify\":true,"
        + "\"phoneNumber\":\"+855123456789\",\"profile\":{\"username\":\"Dara\"},"
        + "\"vehicles\":[{\"id\":\"648123456789135\",\"carBrand\":\"Toyota\"}]}}";
  }

  @Test
  void aDriver_hasItsProfileSynced_andTheMechanicSyncIsLeftAlone() throws Exception {
    UUID id = UUID.randomUUID();

    call("user-context", driver(id));

    verify(userSync).sync(eq(id), any(UserContext.User.class));
    verify(sync, never()).sync(any(), any());
    verify(chain).doFilter(any(), any());
  }

  @Test
  void aMechanic_isNotSyncedAsADriver() throws Exception {
    call(
        "mechanic-context",
        mechanic(UUID.randomUUID(), "\"otpVerify\":true,\"profileVerify\":true,"));

    verify(userSync, never()).sync(any(), any());
  }

  @Test
  void aFailingDriverSync_doesNotStopTheRequest() throws Exception {
    doThrow(new IllegalStateException("db down")).when(userSync).sync(any(), any());

    call("user-context", driver(UUID.randomUUID()));

    verify(chain).doFilter(any(), any());
  }

  @Test
  void aFailingSync_doesNotStopTheRequest() throws Exception {
    doThrow(new IllegalStateException("db down")).when(sync).sync(any(), any());

    call(
        "mechanic-context",
        mechanic(UUID.randomUUID(), "\"otpVerify\":true,\"profileVerify\":true,"));

    verify(chain).doFilter(any(), any());
  }
}
