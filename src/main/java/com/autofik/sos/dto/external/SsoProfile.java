package com.autofik.sos.dto.external;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * The subset of an SSO profile this service projects. Everything else in the payload is ignored.
 *
 * <p>Field names are taken from what SSO's own endpoint documentation accepts on the matching
 * {@code PUT}: {@code username} for a user, {@code fullName} for a mechanic. Aliases cover the
 * plausible variants so a naming mismatch degrades to a null field rather than a failed parse —
 * verify against a real response body and delete the guesses that turn out to be wrong.
 *
 * @param phone the mechanic's screen needs this to call the driver, and it is the one field with
 *              no documented name at all. If it arrives null, check whether it lives on
 *              {@code /info} rather than {@code /profile}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SsoProfile(
        @JsonAlias({"id", "userId", "mechanicId", "user_id"}) UUID id,
        @JsonAlias({"username", "fullName", "name", "full_name"}) String name,
        @JsonAlias({"phone", "phoneNumber", "phone_number"}) String phone,
        @JsonAlias({"email"}) String email
) {}
