package com.autofik.sos.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's trace id in the {@code X-Request-Id} response header, so a failure reported by
 * the app can be found in the logs ({@code [traceId,spanId]}) and in Jaeger by that one value. It
 * is the trace id, not a second id: the caller cannot choose it, and a call that reached us with a
 * W3C {@code traceparent} keeps the gateway's id.
 *
 * <p>Runs just after Boot's server observation filter (HIGHEST_PRECEDENCE + 1), the one that opens
 * the span, and before the security chain, so even a 401 from a filter carries the header. Probes
 * are not traced (see {@link TracingConfig}) and get no header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  // Absent when tracing is switched off.
  private final ObjectProvider<Tracer> tracer;

  public RequestIdFilter(ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Tracer t = tracer.getIfAvailable();
    Span span = t == null ? null : t.currentSpan();
    if (span != null) {
      response.setHeader(HEADER, span.context().traceId());
    }
    chain.doFilter(request, response);
  }
}
