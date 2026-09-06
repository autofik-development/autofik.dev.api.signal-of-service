package com.autofik.sos.service;

import com.autofik.sos.config.SsoConfig;
import com.autofik.sos.dto.external.SsoProfile;
import com.autofik.sos.dto.external.SsoVehicle;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.security.Caller;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads identity and vehicle data from SSO.
 *
 * <p>Two credentials, for two different questions. <em>Who is this and what are their details</em>
 * is answered by the profile and vehicle routes, which SSO scopes to the calling user — so this
 * presents the caller's own bearer, and there is no service-to-service equivalent to fall back on.
 * <em>Is this identity verified</em> is answered by {@code verify-token}, the one route the
 * publishable key opens. A 200 from {@code /profile} proves neither: it is {@code
 * requireAuthForSetup}, which accepts sessions that have not completed onboarding.
 *
 * <p>Timeouts sit well inside the gateway's own route budget (30s for {@code /api/v1/sos/}, 5s for
 * everything else), so a slow SSO surfaces here as a 503 rather than as a gateway 504 over a
 * request that is still running.
 */
@Service
@Slf4j
public class SsoClient {

  private final RestClient http;
  private final ObjectMapper objectMapper;
  private final String publishableApiKey;

  public SsoClient(SsoConfig config, ObjectMapper objectMapper) {
    this.http =
        RestClient.builder().baseUrl(config.getBaseUrl()).requestFactory(timeoutFactory()).build();
    this.objectMapper = objectMapper;
    this.publishableApiKey = config.getPublishableApiKey();
  }

  // Well inside the gateway's route budget, so a slow SSO surfaces as a 503 from here rather
  // than as a gateway 504 over a request that is still running.
  private static SimpleClientHttpRequestFactory timeoutFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofSeconds(2));
    return factory;
  }

  public SsoProfile userProfile() {
    return asCaller("/user/profile", SsoProfile.class);
  }

  public SsoProfile mechanicProfile() {
    return asCaller("/mechanic/profile", SsoProfile.class);
  }

  /** 403 here means "not yours" and "does not exist" alike — the endpoint is caller-scoped. */
  public SsoVehicle vehicle(UUID vehicleId) {
    return asCaller("/user/vehicles/" + vehicleId, SsoVehicle.class);
  }

  /**
   * @param kind {@code user} or {@code mechanic}
   * @return false when SSO answers 401 (profile incomplete) or 404 (no verification record). Both
   *     mean the same thing here: not yet a real, usable identity.
   */
  public boolean isVerified(String kind, UUID id) {
    try {
      http.get()
          .uri("/{kind}/verify-token/{id}", kind, id)
          .header("x-publishable-api-key", publishableApiKey)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.NotFound e) {
      return false;
    } catch (Exception e) {
      log.error("SSO verify-token/{} for {} failed: {}", kind, id, e.getMessage());
      throw new ServiceUnavailableException("Unable to reach SSO to verify identity");
    }
  }

  private <T> T asCaller(String path, Class<T> type) {
    try {
      String body =
          http.get()
              .uri(path)
              .header(HttpHeaders.AUTHORIZATION, Caller.token())
              .retrieve()
              .body(String.class);
      return unwrap(body, type);
    } catch (HttpClientErrorException.NotFound
        | HttpClientErrorException.Forbidden
        | HttpClientErrorException.Unauthorized e) {
      throw new AccessDeniedException("SSO denied " + path);
    } catch (AccessDeniedException e) {
      throw e;
    } catch (Exception e) {
      // Full exception, not just getMessage() — a null-pointer or class-cast here has no
      // message at all, and swallowing it into "Unable to reach SSO" makes an unwrap bug
      // indistinguishable from SSO actually being down.
      log.error("SSO call {} failed", path, e);
      throw new ServiceUnavailableException("Unable to reach SSO");
    }
  }

  /**
   * Accepts a bare object or one wrapped in any depth of {@code data} / {@code user} / {@code
   * mechanic} / {@code vehicle}. Confirmed live: SSO's profile response is {@code
   * data.user.profile.username} — three layers, not one, and {@code phoneNumber} sits as a
   * <em>sibling</em> of {@code profile} at the {@code user} level, not inside it. So this cannot be
   * a plain "descend one more level" fix: replacing root with {@code profile} would expose {@code
   * username} but silently drop {@code phoneNumber} out of scope entirely. Instead, once no more
   * known envelope is found, any remaining {@code profile} object one level down has its fields
   * merged UP into the current level, so both siblings are visible to the same deserialization
   * pass. Tolerating this is cheaper than a redeploy every time SSO changes it.
   */
  private <T> T unwrap(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      throw new AccessDeniedException("Empty SSO response");
    }
    JsonNode root = objectMapper.readTree(body);
    boolean descended;
    do {
      descended = false;
      for (String envelope : new String[] {"data", "user", "mechanic", "vehicle"}) {
        JsonNode nested = root.get(envelope);
        if (nested != null && nested.isObject()) {
          root = nested;
          descended = true;
          break;
        }
      }
    } while (descended);

    JsonNode profile = root.get("profile");
    if (profile != null && profile.isObject() && root.isObject()) {
      ObjectNode merged = ((ObjectNode) root).deepCopy();
      merged.setAll((ObjectNode) profile);
      root = merged;
    }
    return objectMapper.treeToValue(root, type);
  }
}
