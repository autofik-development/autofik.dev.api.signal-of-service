package com.autofik.sos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.autofik.sos.config.BidCommandExecutor;
import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.BidService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.MimeTypeUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real STOMP frames through the real annotation handler, like {@link
 * MechanicLocationSocketControllerTest}: the destination strings, payload field names and reply
 * shapes are the contract the apps code against, and a mismatch is silent.
 */
class BidSocketControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final BidService bidService = mock(BidService.class);
  private final com.autofik.sos.service.SosService sosService =
      mock(com.autofik.sos.service.SosService.class);
  private SimpAnnotationMethodMessageHandler handler;
  private final List<Message<?>> replies = new CopyOnWriteArrayList<>();

  private final UUID callerId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private final UUID bidId = UUID.randomUUID();

  private ExecutorSubscribableChannel replyChannel;

  private BidSocketController controller(BidCommandExecutor executor) {
    return new BidSocketController(
        bidService, sosService, executor, new SimpMessagingTemplate(replyChannel));
  }

  @BeforeEach
  void setUp() {
    replyChannel = new ExecutorSubscribableChannel();
    ExecutorSubscribableChannel channel = replyChannel;
    channel.subscribe(replies::add);
    StaticApplicationContext context = new StaticApplicationContext();
    context
        .getBeanFactory()
        .registerSingleton("bidSocketController", controller(BidCommandExecutor.inline()));
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
  void mechanicPlacesBid_serviceGetsCallerIdentity_andReplyEchoesClientMessageId() {
    BidSosResponse bid = mock(BidSosResponse.class);
    when(bidService.create(any())).thenReturn(bid);

    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":25.5,\"clientMessageId\":\"c-1\"}");

    verify(bidService).create(new CreateBidRequest(callerId, sosId, new BigDecimal("25.5")));
    JsonNode reply = onlyReply("/queue/bid-results");
    assertThat(reply.get("type").asString()).isEqualTo("BID_PLACED");
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("c-1");
  }

  /** The mechanic id comes from the socket; a forged one in the payload is not even a field. */
  @Test
  void mechanicIdInThePayloadIsIgnored() {
    when(bidService.create(any())).thenReturn(mock(BidSosResponse.class));
    UUID forged = UUID.randomUUID();

    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":10,\"mechanicId\":\"" + forged + "\"}");

    verify(bidService).create(new CreateBidRequest(callerId, sosId, new BigDecimal("10")));
  }

  @Test
  void driverCannotPlaceABid() {
    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_USER,
        "{\"sosId\":\"" + sosId + "\",\"price\":10,\"clientMessageId\":\"c-2\"}");

    verify(bidService, never()).create(any());
    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(403);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("c-2");
  }

  @Test
  void badPriceIsRejectedBeforeTheService_andKeepsTheClientMessageId() {
    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":0,\"clientMessageId\":\"c-3\"}");

    verify(bidService, never()).create(any());
    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(400);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("c-3");
  }

  @Test
  void driverAcceptsBid() {
    when(bidService.updateStatus(any(), any())).thenReturn(mock(BidSosResponse.class));

    send(
        "/app/bids/decision",
        GatewayIdentityFilter.ROLE_USER,
        "{\"bidId\":\"" + bidId + "\",\"status\":\"ACCEPTED\",\"clientMessageId\":\"d-1\"}");

    verify(bidService)
        .updateStatus(bidId, new UpdateBidStatusRequest(callerId, BidStatus.ACCEPTED));
    JsonNode reply = onlyReply("/queue/bid-results");
    assertThat(reply.get("type").asString()).isEqualTo("BID_DECIDED");
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("d-1");
  }

  @Test
  void mechanicCannotDecideABid() {
    send(
        "/app/bids/decision",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"bidId\":\"" + bidId + "\",\"status\":\"ACCEPTED\"}");

    verify(bidService, never()).updateStatus(any(), any());
    assertThat(onlyReply("/queue/errors").get("status").asInt()).isEqualTo(403);
  }

  @Test
  void mechanicWithdrawsBid() {
    when(bidService.cancel(bidId, callerId)).thenReturn(mock(BidSosResponse.class));

    send(
        "/app/bids/cancel",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"bidId\":\"" + bidId + "\",\"clientMessageId\":\"w-1\"}");

    JsonNode reply = onlyReply("/queue/bid-results");
    assertThat(reply.get("type").asString()).isEqualTo("BID_WITHDRAWN");
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("w-1");
  }

  @Test
  void driverCannotWithdrawABid() {
    send("/app/bids/cancel", GatewayIdentityFilter.ROLE_USER, "{\"bidId\":\"" + bidId + "\"}");

    verify(bidService, never()).cancel(any(), any());
    assertThat(onlyReply("/queue/errors").get("status").asInt()).isEqualTo(403);
  }

  /** The error status mirrors the REST code the same failure gives. */
  @Test
  void serviceFailuresMapToTheRestStatus() {
    when(bidService.cancel(any(), any()))
        .thenThrow(new ConflictException("Only PENDING bids can be cancelled"))
        .thenThrow(new EntityNotFoundException("Bid not found"))
        .thenThrow(new AccessDeniedException("not yours"))
        .thenThrow(new RuntimeException("db password is hunter2"));

    String frame = "{\"bidId\":\"" + bidId + "\",\"clientMessageId\":\"x\"}";
    for (int i = 0; i < 4; i++)
      send("/app/bids/cancel", GatewayIdentityFilter.ROLE_MECHANIC, frame);

    assertThat(replies).hasSize(4);
    List<Integer> statuses = replies.stream().map(r -> body(r).get("status").asInt()).toList();
    assertThat(statuses).containsExactly(409, 404, 403, 500);
    // A server fault must not leak its message to the client.
    assertThat(body(replies.get(3)).get("error").asString()).isEqualTo("Internal server error");
    assertThat(body(replies.get(2)).get("error").asString()).doesNotContain("not yours");
  }

  @Test
  void syncReturnsASnapshotOfTheCallersBids() {
    when(bidService.getBySosRequest(sosId, null, callerId)).thenReturn(List.of());

    send(
        "/app/bids/sync",
        GatewayIdentityFilter.ROLE_USER,
        "{\"sosId\":\"" + sosId + "\",\"clientMessageId\":\"s-1\"}");

    verify(bidService).getBySosRequest(sosId, (BidSortFilter) null, callerId);
    JsonNode reply = onlyReply("/queue/bid-results");
    assertThat(reply.get("type").asString()).isEqualTo("BIDS_SNAPSHOT");
    assertThat(reply.get("sosId").asString()).isEqualTo(sosId.toString());
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("s-1");
    assertThat(reply.get("bids").isArray()).isTrue();
  }

  /** The bid waits for Google, so it must not run on the shared STOMP inbound thread. */
  @Test
  void placeBid_isHandedToTheBidExecutor_andRepliesFromThere() {
    java.util.List<Runnable> queued = new CopyOnWriteArrayList<>();
    BidCommandExecutor deferred =
        new BidCommandExecutor(1, 1) {
          @Override
          public void execute(Runnable command) {
            queued.add(command);
          }
        };
    rewire(deferred);
    when(bidService.create(any())).thenReturn(mock(BidSosResponse.class));

    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":10,\"clientMessageId\":\"a-1\"}");

    verify(bidService, never()).create(any());
    assertThat(replies).isEmpty();
    queued.forEach(Runnable::run);
    verify(bidService).create(new CreateBidRequest(callerId, sosId, new BigDecimal("10")));
    JsonNode reply = onlyReply("/queue/bid-results");
    assertThat(reply.get("type").asString()).isEqualTo("BID_PLACED");
    assertThat(reply.get("clientMessageId").asString()).isEqualTo("a-1");
  }

  /** A failure inside the executor still reaches the client, with the REST status. */
  @Test
  void placeBid_failureInsideTheExecutor_isAnErrorFrame() {
    when(bidService.create(any())).thenThrow(new ConflictException("window closed"));

    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":10,\"clientMessageId\":\"a-2\"}");

    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(409);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("a-2");
  }

  /** Overload is answered at once so the app can retry, never queued without limit. */
  @Test
  void placeBid_whenTheExecutorIsFull_answers503() {
    BidCommandExecutor full =
        new BidCommandExecutor(1, 1) {
          @Override
          public void execute(Runnable command) {
            throw new java.util.concurrent.RejectedExecutionException("full");
          }
        };
    rewire(full);

    send(
        "/app/bids",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"sosId\":\"" + sosId + "\",\"price\":10,\"clientMessageId\":\"a-3\"}");

    verify(bidService, never()).create(any());
    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(503);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("a-3");
  }

  private com.autofik.sos.dto.event.SosNotificationEvent offer(UUID id) {
    return new com.autofik.sos.dto.event.SosNotificationEvent(
        "SOS_CREATED",
        null,
        "MECHANIC",
        id,
        "New SOS request nearby",
        "Problem: TOW",
        List.of(),
        null);
  }

  @Test
  void syncOpen_resendsEachOfferOnTheOffersQueue_thenAcksWithTheCount() {
    UUID other = UUID.randomUUID();
    when(sosService.openOffersFor(callerId)).thenReturn(List.of(offer(sosId), offer(other)));

    send("/app/sos/open", GatewayIdentityFilter.ROLE_MECHANIC, "{\"clientMessageId\":\"s-1\"}");

    assertThat(replies).hasSize(3);
    assertThat(StompHeaderAccessor.wrap(replies.get(0)).getDestination())
        .endsWith("/queue/sos-new-request");
    assertThat(body(replies.get(0)).get("type").asString()).isEqualTo("SOS_CREATED");
    assertThat(body(replies.get(0)).get("sosId").asString()).isEqualTo(sosId.toString());
    assertThat(body(replies.get(1)).get("sosId").asString()).isEqualTo(other.toString());
    assertThat(StompHeaderAccessor.wrap(replies.get(2)).getDestination())
        .endsWith("/queue/bid-results");
    JsonNode ack = body(replies.get(2));
    assertThat(ack.get("type").asString()).isEqualTo("OPEN_SOS_SYNCED");
    assertThat(ack.get("clientMessageId").asString()).isEqualTo("s-1");
    assertThat(ack.get("count").asInt()).isEqualTo(2);
  }

  @Test
  void syncOpen_withNothingOpen_stillAcks_withZero() {
    when(sosService.openOffersFor(callerId)).thenReturn(List.of());

    send("/app/sos/open", GatewayIdentityFilter.ROLE_MECHANIC, "{}");

    JsonNode ack = onlyReply("/queue/bid-results");
    assertThat(ack.get("count").asInt()).isZero();
  }

  @Test
  void syncOpen_asksForTheSocketsMechanic_notOneNamedInTheFrame() {
    when(sosService.openOffersFor(any())).thenReturn(List.of());

    send(
        "/app/sos/open",
        GatewayIdentityFilter.ROLE_MECHANIC,
        "{\"mechanicId\":\"" + UUID.randomUUID() + "\"}");

    verify(sosService).openOffersFor(callerId);
  }

  @Test
  void syncOpen_aDriverIsRefused() {
    send("/app/sos/open", GatewayIdentityFilter.ROLE_USER, "{\"clientMessageId\":\"s-2\"}");

    verify(sosService, never()).openOffersFor(any());
    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(403);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("s-2");
  }

  @Test
  void syncOpen_whenTheExecutorIsFull_answers503() {
    BidCommandExecutor full =
        new BidCommandExecutor(1, 1) {
          @Override
          public void execute(Runnable command) {
            throw new java.util.concurrent.RejectedExecutionException("full");
          }
        };
    rewire(full);

    send("/app/sos/open", GatewayIdentityFilter.ROLE_MECHANIC, "{\"clientMessageId\":\"s-3\"}");

    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(503);
  }

  @Test
  void syncOpen_aServiceFailure_isAnErrorFrame_notASilentDrop() {
    when(sosService.openOffersFor(callerId)).thenThrow(new EntityNotFoundException("gone"));

    send("/app/sos/open", GatewayIdentityFilter.ROLE_MECHANIC, "{\"clientMessageId\":\"s-4\"}");

    JsonNode error = onlyReply("/queue/errors");
    assertThat(error.get("status").asInt()).isEqualTo(404);
    assertThat(error.get("clientMessageId").asString()).isEqualTo("s-4");
  }

  /** Database and availability failures map the way GlobalExceptionHandler maps them for REST. */
  @Test
  void databaseAndAvailabilityFailures_mapLikeRest() {
    when(bidService.cancel(any(), any()))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_bid_x violated"))
        .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("pool"))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .thenThrow(new com.autofik.sos.exception.ServiceUnavailableException("maps down"))
        .thenThrow(new com.autofik.sos.exception.TooManyRequestsException("slow down"));

    String frame = "{\"bidId\":\"" + bidId + "\",\"clientMessageId\":\"x\"}";
    for (int i = 0; i < 5; i++)
      send("/app/bids/cancel", GatewayIdentityFilter.ROLE_MECHANIC, frame);

    assertThat(replies.stream().map(r -> body(r).get("status").asInt()).toList())
        .containsExactly(409, 503, 503, 503, 429);
    // fixed texts: nothing from the driver message (constraint names, hosts) reaches the client
    assertThat(body(replies.get(0)).get("error").asString())
        .isEqualTo("The request conflicts with existing data");
    assertThat(body(replies.get(1)).get("error").asString())
        .isEqualTo("The service is busy, please retry shortly");
  }

  private void rewire(BidCommandExecutor executor) {
    StaticApplicationContext context = new StaticApplicationContext();
    context.getBeanFactory().registerSingleton("bidSocketController", controller(executor));
    handler =
        new SimpAnnotationMethodMessageHandler(
            replyChannel, replyChannel, new SimpMessagingTemplate(replyChannel));
    handler.setApplicationContext(context);
    handler.setDestinationPrefixes(List.of("/app"));
    handler.setMessageConverter(new JacksonJsonMessageConverter());
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    handler.setValidator(validator);
    handler.afterPropertiesSet();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private JsonNode onlyReply(String queue) {
    assertThat(replies).hasSize(1);
    Message<?> reply = replies.get(0);
    assertThat(StompHeaderAccessor.wrap(reply).getDestination()).endsWith(queue);
    return body(reply);
  }

  private static JsonNode body(Message<?> message) {
    // The bare template used here does not convert the reply; the real broker channel does.
    return JSON.valueToTree(message.getPayload());
  }

  private void send(String destination, String role, String json) {
    Authentication caller =
        new UsernamePasswordAuthenticationToken(
            callerId, null, List.of(new SimpleGrantedAuthority(role)));
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination(destination);
    accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
    accessor.setSessionId("test-session");
    accessor.setSessionAttributes(new HashMap<>());
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);
    handler.handleMessage(
        MessageBuilder.createMessage(
            json.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders()));
  }
}
