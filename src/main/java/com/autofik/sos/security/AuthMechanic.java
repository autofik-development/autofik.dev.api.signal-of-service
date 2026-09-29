package com.autofik.sos.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller on a route only a mechanic may reach, and turns anyone else away.
 *
 * <p>The mechanic half of {@link AuthUser}: same {@link CurrentUser}, except that a driver asking
 * for a handler that declares this parameter is answered 403 before the handler runs. Use it for
 * bidding, arrival and completion — the actions where acting as the wrong role is not a formatting
 * mistake but a stranger taking over someone's job.
 *
 * <p>Only valid on routes behind {@code AuthMiddleware} (see {@code WebConfig#addInterceptors});
 * elsewhere nothing resolved a caller at all and the parameter arrives null, exactly as {@link
 * AuthUser} does, rather than pretending to a check that never ran.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthMechanic {}
