package com.autofik.sos.service.impl;

import com.autofik.sos.config.BillingConfig;
import com.autofik.sos.config.TracingConfig;
import com.autofik.sos.dto.external.BillingVerifyResponse;
import com.autofik.sos.exception.PlanRequiredException;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.BillingClient;
import com.fasterxml.jackson.annotation.JsonInclude;
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

  private record VerifyRequest(
      String userId, @JsonInclude(JsonInclude.Include.NON_NULL) String role) {}

  private final RestClient restClient; // null while billing is not configured
  private final String verifyPath;

  /** Unobserved; for tests that build the client by hand. */
  public BillingClientImpl(BillingConfig config) {
    this(config, ObservationRegistry.NOOP);
  }

  @Autowired
  public BillingClientImpl(BillingConfig config, ObservationRegistry observationRegistry) {
    this.verifyPath = config.getVerifyPath();
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
    try {
      BillingVerifyResponse response =
          restClient
              .post()
              .uri(verifyPath)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new VerifyRequest(userId.toString(), role))
              .retrieve()
              .body(BillingVerifyResponse.class);
      if (response == null || response.data() == null) {
        log.error("Billing verify answered without data");
        throw new ServiceUnavailableException("Unable to verify the billing plan");
      }
      return response.data();
    } catch (HttpClientErrorException.NotFound e) {
      // ponytail: 404 is assumed to mean "no billing account"; billing to confirm.
      throw new PlanRequiredException(PlanRequiredException.Reason.NO_PLAN);
    } catch (ServiceUnavailableException e) {
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
}
