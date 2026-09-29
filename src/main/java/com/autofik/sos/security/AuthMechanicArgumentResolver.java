package com.autofik.sos.security;

import com.autofik.sos.middleware.AuthMiddleware;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands {@link AuthMechanic} parameters the caller the middleware published as a mechanic, and
 * refuses the request when the caller is a driver.
 *
 * <p>The refusal belongs here rather than in each handler because this is the one place that
 * already knows both what the route asked for and who arrived. It is an {@link
 * AccessDeniedException} so it lands in the same 403 body every other refusal on this service uses
 * — {@code GlobalExceptionHandler} for a dispatched request, the chain's handler otherwise.
 *
 * <p>Registered alongside {@link AuthUserArgumentResolver} in {@code WebConfig}, and for the same
 * reason: without it the parameter would fall through to Spring's model-attribute resolver, which
 * would build a {@code CurrentUser} out of query parameters and let the caller name themselves a
 * mechanic.
 */
@Component
public class AuthMechanicArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(AuthMechanic.class)
        && CurrentUser.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object mechanic = attribute(webRequest, AuthMiddleware.CURRENT_MECHANIC_ATTRIBUTE);
    if (mechanic != null) {
      return mechanic;
    }
    if (attribute(webRequest, AuthMiddleware.CURRENT_USER_ATTRIBUTE) != null) {
      // Someone the gateway verified, but as a driver. Distinct from the null below, which means
      // no middleware ran and so nobody was checked at all.
      throw new AccessDeniedException("This action is only available to mechanics");
    }
    return null;
  }

  private static Object attribute(NativeWebRequest request, String name) {
    return request.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
  }
}
