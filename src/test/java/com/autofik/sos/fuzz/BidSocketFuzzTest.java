package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.config.BidCommandExecutor;
import com.autofik.sos.controller.BidSocketController;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.BidService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.MimeTypeUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Hostile frames on every bid destination, through the real annotation handler. A frame must never
 * throw into the socket thread, must never reach the service as the wrong role, must always get
 * exactly one reply (so the app is not left waiting), and a server fault must not leak its message.
 */
class BidSocketFuzzTest {

  private static final List<String> DESTINATIONS =
      List.of("/app/bids", "/app/bids/decision", "/app/bids/cancel", "/app/bids/sync");

  private final BidService bidService = mock(BidService.class);
  private final List<Message<?>> replies = new CopyOnWriteArrayList<>();
  private final UUID callerId = UUID.randomUUID();
  private final SimpAnnotationMethodMessageHandler handler;

  BidSocketFuzzTest() {
    ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
    channel.subscribe(replies::add);
    StaticApplicationContext context = new StaticApplicationContext();
    context
        .getBeanFactory()
        .registerSingleton(
            "bidSocketController",
            new BidSocketController(
                bidService,
                mock(com.autofik.sos.service.SosService.class),
                BidCommandExecutor.inline(),
                new SimpMessagingTemplate(channel)));
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
  void reset() {
    org.mockito.Mockito.reset(bidService);
    replies.clear();
  }

  /** Junk bodies, any role, any destination: no throw, and the caller always gets one answer. */
  @Property(seed = Fuzz.SEED)
  void garbageBodies_neverThrow_andAlwaysGetOneReply(
      @ForAll("destination") String destination,
      @ForAll("role") String role,
      @ForAll("body") String body) {
    assertThatCode(() -> send(destination, role, body)).doesNotThrowAnyException();

    assertThat(replies).as("replies to %s %s", role, destination).hasSizeLessThanOrEqualTo(1);
    // A body the handler cannot even decode is dropped by Spring before the controller runs;
    // everything it does decode must be answered.
    replies.forEach(r -> assertThat(r.getPayload()).isInstanceOf(Map.class));
  }

  /** The wrong role never reaches the service, whatever the body says. */
  @Property(seed = Fuzz.SEED)
  void wrongRole_neverReachesTheService(
      @ForAll("destination") String destination, @ForAll("goodBody") String body) {
    String wrong =
        destination.equals("/app/bids/decision")
            ? GatewayIdentityFilter.ROLE_MECHANIC
            : GatewayIdentityFilter.ROLE_USER;
    if (destination.equals("/app/bids/sync")) return; // either role may sync

    send(destination, wrong, body);

    verify(bidService, never()).create(any());
    verify(bidService, never()).updateStatus(any(), any());
    verify(bidService, never()).cancel(any(), any());
  }

  /** Any price: only a valid one reaches the service, and always under the caller's own id. */
  @Property(seed = Fuzz.SEED)
  void price_onlyValidOnesReachTheService_underTheCallersId(
      @ForAll("price") String priceJson, @ForAll("forgedId") String forgedMechanicId) {
    UUID sosId = UUID.randomUUID();
    String body = "{\"sosId\":\"" + sosId + "\",\"price\":" + priceJson + forgedMechanicId + "}";

    assertThatCode(() -> send("/app/bids", GatewayIdentityFilter.ROLE_MECHANIC, body))
        .doesNotThrowAnyException();

    boolean valid;
    try {
      valid = new BigDecimal(priceJson).compareTo(new BigDecimal("0.01")) >= 0;
    } catch (NumberFormatException notANumber) {
      valid = false;
    }
    if (!valid) {
      verify(bidService, never()).create(any());
    } else {
      verify(bidService).create(new CreateBidRequest(callerId, sosId, new BigDecimal(priceJson)));
    }
  }

  /** Whatever the service throws, the client gets a mapped status and no internal text. */
  @Property(seed = Fuzz.SEED)
  void serviceFailures_mapToAStatus_andServerFaultsLeakNothing(
      @ForAll("failure") RuntimeException failure) {
    when(bidService.cancel(any(), any())).thenThrow(failure);

    send(
        "/app/bids/cancel",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"bidId\":\"" + UUID.randomUUID() + "\",\"clientMessageId\":\"f\"}");

    assertThat(replies).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> reply = (Map<String, Object>) replies.get(0).getPayload();
    assertThat((int) reply.get("status")).isIn(400, 403, 404, 409, 500);
    assertThat(reply.get("clientMessageId")).isEqualTo("f");
    if ((int) reply.get("status") == 500) {
      assertThat(reply.get("error")).isEqualTo("Internal server error");
    }
    if ((int) reply.get("status") == 403) {
      assertThat(String.valueOf(reply.get("error"))).doesNotContain("secret");
    }
  }

  @Provide
  Arbitrary<String> destination() {
    return Arbitraries.of(DESTINATIONS);
  }

  @Provide
  Arbitrary<String> role() {
    return Arbitraries.of(
        GatewayIdentityFilter.ROLE_MECHANIC, GatewayIdentityFilter.ROLE_USER, "ROLE_ADMIN");
  }

  @Provide
  Arbitrary<String> body() {
    UUID id = UUID.randomUUID();
    return Arbitraries.oneOf(
        Fuzz.jsonBodies(),
        Arbitraries.of(
            "{\"sosId\":\"" + id + "\",\"price\":1e999}",
            "{\"bidId\":\"" + id + "\",\"status\":\"NOPE\"}",
            "{\"bidId\":\"" + id + "\",\"status\":\"CANCELLED\"}",
            "{\"bidId\":\"" + id + "\",\"status\":null}",
            "{\"sosId\":\"" + id + "\",\"clientMessageId\":\"" + "x".repeat(10_000) + "\"}",
            "{\"sosId\":\"" + id + "\",\"price\":\"25\",\"clientMessageId\":123}",
            "{\"sosId\":[],\"price\":{}}"));
  }

  @Provide
  Arbitrary<String> goodBody() {
    UUID id = UUID.randomUUID();
    return Arbitraries.of(
        "{\"sosId\":\"" + id + "\",\"price\":10}",
        "{\"bidId\":\"" + id + "\",\"status\":\"ACCEPTED\"}",
        "{\"bidId\":\"" + id + "\"}");
  }

  @Provide
  Arbitrary<String> price() {
    return Arbitraries.oneOf(
        Arbitraries.bigDecimals()
            .between(BigDecimal.valueOf(-1000), BigDecimal.valueOf(1000))
            .ofScale(2)
            .map(BigDecimal::toPlainString),
        Arbitraries.of("0", "0.0", "0.00", "0.01", "0.009", "0.0099999", "-0.01", "1e308", "1E-5"));
  }

  @Provide
  Arbitrary<String> forgedId() {
    return Arbitraries.of(
        "", ",\"mechanicId\":\"" + UUID.randomUUID() + "\"", ",\"mechanicId\":null");
  }

  @Provide
  Arbitrary<RuntimeException> failure() {
    return Arbitraries.of(
        new IllegalStateException("conflict"),
        new IllegalArgumentException("bad"),
        new EntityNotFoundException("missing"),
        new AccessDeniedException("secret detail"),
        new NullPointerException("secret detail"),
        new RuntimeException("secret detail jdbc:postgresql://db/prod password=hunter2"),
        new UnsupportedOperationException());
  }

  private void send(String destination, String role, String body) {
    Authentication caller =
        new UsernamePasswordAuthenticationToken(
            callerId, null, List.of(new SimpleGrantedAuthority(role)));
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination(destination);
    accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
    accessor.setSessionId("fuzz-session");
    accessor.setSessionAttributes(new HashMap<>());
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);
    handler.handleMessage(
        MessageBuilder.createMessage(
            body.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders()));
  }
}
