package com.autofik.sos.service.impl;

import com.autofik.sos.config.MedusaConfig;
import com.autofik.sos.dto.external.MedusaServiceGroupResponse;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.service.MedusaService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Production implementation of {@link MedusaService} utilizing Spring's modern {@link RestClient}
 * to communicate synchronously with the external Medusa API.
 */
@Service
@Slf4j
public class MedusaServiceImpl implements MedusaService {

  /** Pre-configured, thread-safe HTTP client instance for executing requests to Medusa. */
  private final RestClient restClient;

  /**
   * Constructs a new {@code MedusaServiceImpl} and initializes the underlying HTTP client wrapper
   * using the base configurations provided by {@link MedusaConfig}.
   *
   * @param config The injected configuration properties containing base URLs and API keys.
   */
  public MedusaServiceImpl(MedusaConfig config) {
    this.restClient =
        RestClient.builder()
            .baseUrl(config.getBaseUrl())
            .defaultHeader("x-publishable-api-key", config.getPublicKey())
            .build();
  }

  /**
   * Resolves the operational key for a specific service group by querying Medusa's store endpoint.
   *
   * <p>This method handles external HTTP 404 statuses as explicit resource misses, translating them
   * into standard persistence layer exceptions for downstream uniformity.
   *
   * @param serviceGroupId The unique structural ID of the service group within Medusa.
   * @return The functional identifier key mapped to the targeted group.
   * @throws EntityNotFoundException If Medusa returns an empty body or an explicit HTTP 404 Status.
   * @throws IllegalStateException If a connection timeout, bad handshake, or server error occurs.
   */
  @Override
  public String resolveServiceGroupKey(String serviceGroupId) {
    try {
      MedusaServiceGroupResponse response =
          restClient
              .get()
              .uri("/store/service-groups/{id}", serviceGroupId)
              .retrieve()
              .body(MedusaServiceGroupResponse.class);

      // Catch cases where the request completes successfully but payload structures are missing
      if (response == null || response.serviceGroup() == null) {
        throw new EntityNotFoundException("Service group not found: " + serviceGroupId);
      }

      return response.serviceGroup().key().toUpperCase();
    } catch (HttpClientErrorException.NotFound e) {
      // Explicit 404 from external API map nicely to domestic entity misses
      throw new EntityNotFoundException("Service group not found: " + serviceGroupId);
    } catch (EntityNotFoundException e) {
      // Propagating native missing-record exceptions upwards cleanly
      throw e;
    } catch (Exception e) {
      // Log full network errors or timeouts locally before wrapping in an immutable bubble
      log.error(
          "Failed to fetch service group '{}' from Medusa: {}", serviceGroupId, e.getMessage());
      throw new ServiceUnavailableException("Unable to reach Medusa to validate service group");
    }
  }
}
