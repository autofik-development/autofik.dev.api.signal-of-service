package com.autofik.sos.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Access to the identity {@link GatewayIdentityFilter} resolved for the current request.
 *
 * <p>Controllers normally take {@code @AuthenticationPrincipal UUID} directly; this is for the
 * places that cannot — a service that needs the caller's bearer, or a legacy request field that
 * has to be checked against the real identity before it is deleted next release.
 */
public final class Caller {

    private Caller() {
    }

    /**
     * Rejects a {@code userId} / {@code mechanicId} still being sent in a body or query string when
     * it disagrees with the proven identity. The claimed value is never acted on either way.
     */
    public static void mustMatch(UUID caller, UUID claimed) {
        if (caller == null || (claimed != null && !claimed.equals(caller))) {
            throw new AccessDeniedException("Identity mismatch");
        }
    }

    /**
     * The caller's SSO bearer, for the profile and vehicle reads that SSO scopes to its owner.
     *
     * @throws AccessDeniedException when the request carried no token — those reads cannot be
     *                               retried later from a background job, so failing here is the
     *                               only honest answer.
     */
    public static String token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object token = auth == null ? null : auth.getCredentials();
        if (token == null) {
            throw new AccessDeniedException("No caller token available for SSO");
        }
        return (String) token;
    }
}
