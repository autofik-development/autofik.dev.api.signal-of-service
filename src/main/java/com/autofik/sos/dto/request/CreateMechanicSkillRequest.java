package com.autofik.sos.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * A skill entry declaring what work a mechanic can take on.
 *
 * <p>skillTags are matched against the SOS problem type during dispatch, so each tag must equal the
 * Medusa service group key for the work it covers (e.g. "FLAT_TIRE"). Matching is case-insensitive
 * — tags are normalised to upper case on write. A mechanic with no skill entry is never dispatched.
 */
public record CreateMechanicSkillRequest(
    @NotEmpty(message = "at least one skill tag is required") List<@NotBlank String> skillTags,
    String vehicleSpecialization) {}
