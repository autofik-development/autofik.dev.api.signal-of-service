package com.autofik.sos.service;

/**
 * Service interface for interacting with and managing Medusa service integrations.
 * Provides the business logic abstraction layer for resolving and handling Medusa-specific entities.
 */
public interface MedusaService {

    /**
     * Resolves the functional group key associated with a given Medusa service group identifier.
     *
     * @param serviceGroupId The unique identifier of the service group to look up.
     * @return The functional string key assigned to the group (e.g., used for specific business routing).
     * @throws RuntimeException if the service group cannot be found or the external API call fails.
     */
    String resolveServiceGroupKey(String serviceGroupId);
}
