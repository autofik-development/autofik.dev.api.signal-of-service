package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.autofik.sos.controller.MechanicLocationSocketController;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.MechanicService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.MimeTypeUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Real frames through the real annotation handler (same wiring as
 * MechanicLocationSocketControllerTest), with hostile payloads. A bad frame must be dropped
 * quietly: it must not throw into the socket thread, and it must never reach the service.
 */
class MechanicLocationSocketFuzzTest {

  private final MechanicService mechanicService = mock(MechanicService.class);
  private final UUID mechanicId = UUID.randomUUID();
  private final SimpAnnotationMethodMessageHandler handler;

  MechanicLocationSocketFuzzTest() {
    StaticApplicationContext context = new StaticApplicationContext();
    context
        .getBeanFactory()
        .registerSingleton(
            "mechanicLocationSocketController",
            new MechanicLocationSocketController(mechanicService));
    ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
    handler =
        new SimpAnnotationMethodMessageHandler(
            channel, channel, new SimpMessagingTemplate(channel));
    handler.setApplicationContext(context);
    handler.setDestinationPrefixes(List.of("/app"));
    handler.setMessageConverter(new JacksonJsonMessageConverter());
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    handler.setValidator(validator);
    handler.afterPropertiesSet();
  }

  @BeforeTry
  void resetMock() {
    reset(mechanicService);
  }

  /** Structured pings: any coordinates, any sosId. The service sees only valid ones. */
  @Property(seed = Fuzz.SEED)
  void coordinates_onlyInsideCambodiaReachTheService(
      @ForAll("coordinate") double lat,
      @ForAll("coordinate") double lng,
      @ForAll("sosIdJson") String sosIdJson) {
    String json = "{\"latitude\":" + lat + ",\"longitude\":" + lng + sosIdJson + "}";

    assertThatCode(() -> send(auth(GatewayIdentityFilter.ROLE_MECHANIC), json))
        .doesNotThrowAnyException();

    boolean inBounds =
        Double.isFinite(lat)
            && Double.isFinite(lng)
            && lat >= 10.41
            && lat <= 14.69
            && lng >= 102.5
            && lng <= 107.63;
    if (!inBounds) {
      verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
    }
  }

  /** Raw junk as the frame body, including from a non-mechanic: never a throw, never a call. */
  @Property(seed = Fuzz.SEED)
  void garbageBodies_areDroppedQuietly(
      @ForAll("jsonBody") String body, @ForAll("role") String role) {
    assertThatCode(() -> send(auth(role), body)).doesNotThrowAnyException();

    if (!GatewayIdentityFilter.ROLE_MECHANIC.equals(role)) {
      verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
    }
  }

  @Provide
  Arbitrary<String> jsonBody() {
    return Fuzz.jsonBodies();
  }

  @Provide
  Arbitrary<String> role() {
    return Arbitraries.of(
        GatewayIdentityFilter.ROLE_MECHANIC, GatewayIdentityFilter.ROLE_USER, "ROLE_ADMIN");
  }

  @Provide
  Arbitrary<Double> coordinate() {
    // Mostly random in a range that straddles Cambodia, plus the exact box edges and the values
    // that break comparisons (NaN, infinities, signed zero).
    return Arbitraries.oneOf(
        Arbitraries.doubles().between(-200, 200),
        Arbitraries.of(
            10.41,
            14.69,
            102.5,
            107.63,
            10.4099999,
            14.6900001,
            102.4999999,
            107.6300001,
            0.0,
            -0.0,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY));
  }

  @Provide
  Arbitrary<String> sosIdJson() {
    return Arbitraries.of(
        "",
        ",\"sosId\":null",
        ",\"sosId\":\"" + UUID.randomUUID() + "\"",
        ",\"sosId\":\"not-a-uuid\"",
        ",\"sosId\":\"\"",
        ",\"sosId\":123");
  }

  private Authentication auth(String role) {
    return new UsernamePasswordAuthenticationToken(
        mechanicId, null, List.of(new SimpleGrantedAuthority(role)));
  }

  private void send(Authentication caller, String body) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination("/app/mechanic/location");
    accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
    accessor.setSessionId("fuzz-session");
    accessor.setSessionAttributes(new HashMap<>());
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);
    Message<byte[]> message =
        MessageBuilder.createMessage(
            body.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    handler.handleMessage(message);
  }
}
