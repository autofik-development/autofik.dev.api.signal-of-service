package com.autofik.sos.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.MechanicService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Routes real STOMP frames through the real annotation handler rather than calling the controller
 * directly. The destination string and the payload field names are the contract the mechanic app
 * codes against, and a mismatch in either is silent — the frame is dropped with no error anywhere.
 * Calling {@code ping} by hand would not have caught that.
 */
class MechanicLocationSocketControllerTest {

  private final MechanicService mechanicService = mock(MechanicService.class);
  private SimpAnnotationMethodMessageHandler handler;

  private final UUID mechanicId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
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

  @Test
  void mechanicPingUpdatesLocation() {
    UUID sosId = UUID.randomUUID();
    send(
        auth(GatewayIdentityFilter.ROLE_MECHANIC),
        "{\"latitude\":11.55,\"longitude\":104.92,\"sosId\":\"" + sosId + "\"}");

    verify(mechanicService).updateLocation(mechanicId, 11.55, 104.92, sosId);
  }

  @Test
  void sosIdIsOptional() {
    send(auth(GatewayIdentityFilter.ROLE_MECHANIC), "{\"latitude\":11.55,\"longitude\":104.92}");

    verify(mechanicService).updateLocation(mechanicId, 11.55, 104.92, null);
  }

  /** A connected customer must not be able to drive a mechanic's marker across their own map. */
  @Test
  void userRoleIsRejected() {
    send(auth(GatewayIdentityFilter.ROLE_USER), "{\"latitude\":11.55,\"longitude\":104.92}");

    verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
  }

  /** Outside the Cambodia bounding box — @Valid must stop it before it reaches the service. */
  @Test
  void outOfRangeCoordinatesAreRejected() {
    send(auth(GatewayIdentityFilter.ROLE_MECHANIC), "{\"latitude\":48.85,\"longitude\":2.35}");

    verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
  }

  @Test
  void missingCoordinatesAreRejected() {
    send(auth(GatewayIdentityFilter.ROLE_MECHANIC), "{\"longitude\":104.92}");

    verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
  }

  private Authentication auth(String role) {
    return new UsernamePasswordAuthenticationToken(
        mechanicId, null, List.of(new SimpleGrantedAuthority(role)));
  }

  private void send(Authentication caller, String json) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination("/app/mechanic/location");
    accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
    accessor.setSessionId("test-session");
    accessor.setSessionAttributes(new HashMap<>());
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);

    Message<byte[]> message =
        MessageBuilder.createMessage(
            json.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    handler.handleMessage(message);
  }
}
