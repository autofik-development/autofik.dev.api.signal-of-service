package com.autofik.sos.dto.external;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object (DTO) representing the wrapper response for a Medusa service group.
 * Used to unmarshal incoming JSON payloads from the external Medusa API service.
 *
 * @param serviceGroup The nested service group item details.
 */
public record MedusaServiceGroupResponse(
        @JsonProperty("service_group") ServiceGroupItem serviceGroup
) {
    /**
     * Represents the specific properties of a single Service Group.
     *
     * @param id    The unique identifier of the service group.
     * @param key   The unique functional code or identifier key for the group.
     * @param label The human-readable display name of the service group.
     */
    public record ServiceGroupItem(String id, String key, String label) {}
}
