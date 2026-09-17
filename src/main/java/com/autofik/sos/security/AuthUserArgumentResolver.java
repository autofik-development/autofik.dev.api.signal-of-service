package com.autofik.sos.security;

import com.autofik.sos.middleware.AuthMiddleware;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.ServletModelAttributeMethodProcessor;

/**
 * Hands {@link AuthUser} parameters the {@link CurrentUser} the middleware stored on the request.
 *
 * <p>Registered ahead of Spring's own resolvers in {@code WebConfig}: without it a {@code
 * CurrentUser} parameter would fall through to {@link ServletModelAttributeMethodProcessor}, which
 * builds one out of query parameters — the caller naming themselves, which is the one thing this
 * whole path exists to prevent.
 */
@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(AuthUser.class)
        && CurrentUser.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    return webRequest.getAttribute(
        AuthMiddleware.CURRENT_USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
  }
}
