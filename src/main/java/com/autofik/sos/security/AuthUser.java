package com.autofik.sos.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the identity {@code AuthMiddleware} already resolved onto the request.
 *
 * <p>Use it in place of {@code @AuthenticationPrincipal UUID} where the handler needs more than an
 * id — the caller's role, their device, or which service proxied the call.
 *
 * <p>Only valid on routes behind the middleware (see {@code WebConfig#addInterceptors}); elsewhere
 * the parameter arrives null rather than half-populated, because a partly-known caller is one a
 * handler would go on to act for.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthUser {}
