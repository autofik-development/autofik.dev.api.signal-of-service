package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<Tracer> provider(Tracer tracer) {
    ObjectProvider<Tracer> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(tracer);
    return p;
  }

  private static MockHttpServletResponse run(Tracer tracer) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);
    new RequestIdFilter(provider(tracer))
        .doFilter(new MockHttpServletRequest("GET", "/api/v1/sos"), response, chain);
    return response;
  }

  @Test
  void aTracedRequest_getsItsTraceIdInTheHeader() throws Exception {
    Tracer tracer = mock(Tracer.class);
    Span span = mock(Span.class);
    TraceContext context = mock(TraceContext.class);
    when(tracer.currentSpan()).thenReturn(span);
    when(span.context()).thenReturn(context);
    when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");

    assertThat(run(tracer).getHeader("X-Request-Id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
  }

  @Test
  void aRequestWithNoSpan_getsNoHeader() throws Exception {
    Tracer tracer = mock(Tracer.class); // currentSpan() is null: a probe, which is not traced

    assertThat(run(tracer).getHeader("X-Request-Id")).isNull();
  }

  @Test
  void withTracingSwitchedOff_thereIsNoTracerAndNoHeader() throws Exception {
    assertThat(run(null).getHeader("X-Request-Id")).isNull();
  }
}
