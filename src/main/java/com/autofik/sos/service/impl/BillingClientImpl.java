package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.config.TracingConfig;
import com.autofik.sos.dto.external.BillingConsumeResponse;
import com.autofik.sos.dto.external.BillingVerifyResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.BillingClient;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** {@link BillingClient} over {@link RestClient}, built like {@link MedusaServiceImpl}. */
@Service
@Slf4j
public class BillingClientImpl implements BillingClient {

  private record ConsumeRequest(String userId, String requestId, String userRole) {}

  private final RestClient restClient; // null while billing is not configured
  private final String verifyPath;
  private final String consumePath;

  /** Unobserved; for tests that build the client by hand. */
  public BillingClientImpl(BillingConfig config) {
    this(config, ObservationRegistry.NOOP);
  }

  @Autowired
  public BillingClientImpl(BillingConfig config, ObservationRegistry observationRegistry) {
    this.verifyPath = config.getVerifyPath();
    this.consumePath = config.getConsumePath();
    if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
      this.restClient = null;
      return;
    }
    // Without timeouts a hung billing service holds the caller's request thread.
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .build());
    requestFactory.setReadTimeout(Duration.ofMillis(config.getReadTimeoutMs()));
    this.restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(config.getBaseUrl())
            .defaultHeader("X-Service-Key", config.getServiceKey())
            .observationRegistry(observationRegistry)
            .observationConvention(new TracingConfig.QuerylessClientRequestConvention())
            .build();
  }

  @Override
  public BillingVerifyResponse.Data verify(UUID userId, String role) {
    if (restClient == null) {
      throw new ServiceUnavailableException("Billing is not configured");
    }
    // Billing answers 400 without userRole, so a missing one is our bug, not the customer's.
    if (role == null || role.isBlank()) {
      log.error("Billing verify needs a role for {}", userId);
      throw new ServiceUnavailableException("Unable to verify the billing plan");
    }
    try {
      BillingVerifyResponse response =
          restClient
              .get()
              .uri(
                  uri ->
                      uri.path(verifyPath)
                          .queryParam("userId", userId)
                          .queryParam("userRole", role)
                          .build())
              .retrieve()
              .body(BillingVerifyResponse.class);
      if (response == null || response.data() == null) {
        log.error("Billing verify answered without data");
        throw new ServiceUnavailableException("Unable to verify the billing plan");
      }
      BillingVerifyResponse.Data data = response.data();
      // No paid plan is a 200 with no billingId, not a 404.
      if (data.billingId() == null && !Boolean.TRUE.equals(data.canUse())) {
        throw new PlanRequiredException(PlanRequiredException.Reason.NO_PLAN);
      }
      return data;
    } catch (HttpClientErrorException.NotFound e) {
      // Not in billing's doc for verify; kept in case billing answers it for an unknown user.
      throw new PlanRequiredException(PlanRequiredException.Reason.NO_PLAN);
    } catch (ServiceUnavailableException | PlanRequiredException e) {
      throw e;
    } catch (HttpClientErrorException e) {
      // Any other 4xx is our request or our key being wrong, not the customer's problem.
      log.error(
          "Billing refused the verify call with {}: check the key and the contract",
          e.getStatusCode());
      throw new ServiceUnavailableException("Unable to verify the billing plan");
    } catch (Exception e) {
      log.error("Billing verify failed: {}", e.toString());
      throw new ServiceUnavailableException("Unable to verify the billing plan");
    }
  }

  @Override
  public BillingConsumeResponse.Data consume(UUID userId, String requestId, String role) {
    if (restClient == null) {
      throw new ServiceUnavailableException("Billing is not configured");
    }
    try {
      BillingConsumeResponse response =
          restClient
              .post()
              .uri(consumePath)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new ConsumeRequest(userId.toString(), requestId, role))
              .retrieve()
              .body(BillingConsumeResponse.class);
      if (response == null || response.data() == null) {
        log.error("Billing consume answered without data for {}", requestId);
        throw new ServiceUnavailableException("Unable to cut the token");
      }
      return response.data();
    } catch (HttpClientErrorException e) {
      String message = e.getResponseBodyAsString();
      int code = e.getStatusCode().value();
      if (code == 404) {
        throw new PlanRequiredException(PlanRequiredException.Reason.NO_PLAN);
      }
      if (code == 403 && message.contains("expired")) {
        throw new PlanRequiredException(PlanRequiredException.Reason.EXPIRED);
      }
      if (code == 409 && message.contains("No tokens left")) {
        throw new PlanRequiredException(PlanRequiredException.Reason.NO_TOKENS);
      }
      // 409 "already being processed" clears by itself; retrying the same requestId is safe. The
      // rest (400, 401, role mismatch, requestId used for another user) is our request or key.
      log.error("Billing refused consume for {} with {}: {}", requestId, code, message);
      throw new ServiceUnavailableException("Unable to cut the token");
    } catch (ServiceUnavailableException e) {
      throw e;
    } catch (Exception e) {
      log.error("Billing consume failed for {}: {}", requestId, e.toString());
      throw new ServiceUnavailableException("Unable to cut the token");
    }
  }
}
