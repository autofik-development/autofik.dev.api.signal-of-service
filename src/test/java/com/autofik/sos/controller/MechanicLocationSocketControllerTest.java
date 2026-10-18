package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.MechanicService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes real STOMP frames through the real annotation handler rather than calling the controller
 * directly. The destination string and the payload field names are the contract the mechanic app
 * codes against, and a mismatch in either is silent — the frame is dropped with no error anywhere.
 * Calling {@code ping} by hand would not have caught that.
 */
class MechanicLocationSocketControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final MechanicService mechanicService = mock(MechanicService.class);
  private SimpAnnotationMethodMessageHandler handler;
  private final List<Message<?>> replies = new CopyOnWriteArrayList<>();

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
    channel.subscribe(replies::add);
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
    JsonNode error = onlyError();
    assertThat(error.get("status").asInt()).isEqualTo(403);
    assertThat(error.get("error").asString())
        .isEqualTo("You are not allowed to perform this action");
  }

  /**
   * Outside the Cambodia bounding box — @Valid must stop it before it reaches the service, and the
   * reply must not echo the validation exception (it names the controller method and package).
   */
  @Test
  void outOfRangeCoordinatesAreRejected() {
    send(auth(GatewayIdentityFilter.ROLE_MECHANIC), "{\"latitude\":48.85,\"longitude\":2.35}");

    verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
    JsonNode error = onlyError();
    assertThat(error.get("status").asInt()).isEqualTo(400);
    assertThat(error.get("error").asString()).isEqualTo("Invalid message");
  }

  @Test
  void missingCoordinatesAreRejected() {
    send(auth(GatewayIdentityFilter.ROLE_MECHANIC), "{\"longitude\":104.92}");

    verify(mechanicService, never()).updateLocation(any(), anyDouble(), anyDouble(), any());
  }

  private JsonNode onlyError() {
    assertThat(replies).hasSize(1);
    assertThat(StompHeaderAccessor.wrap(replies.get(0)).getDestination()).endsWith("/queue/errors");
    // The bare template used here does not convert the reply; the real broker channel does.
    return JSON.valueToTree(replies.get(0).getPayload());
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
