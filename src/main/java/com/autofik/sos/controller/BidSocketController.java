package com.autofik.sos.controller;

import com.autofik.sos.config.BidCommandExecutor;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.exception.ServiceUnavailableException;
import com.autofik.sos.exception.TooManyRequestsException;
import com.autofik.sos.security.GatewayIdentityFilter;
import com.autofik.sos.service.BidService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Place, decide and withdraw a bid over the open socket, with the answer on the same socket.
 *
 * <p>Every method calls the same {@link BidService} method the REST endpoint calls, so the row
 * lock, the validation and every notification stay in one place. The REST endpoints stay as the
 * fallback for a dropped socket and for a cold start.
 *
 * <p>Each request carries a {@code clientMessageId} that is echoed in the reply, success or
 * failure, so the app can match an answer to the request that caused it.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BidSocketController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final BidService bidService;
  private final BidCommandExecutor bidCommandExecutor;
  private final SimpMessagingTemplate messagingTemplate;

  public record PlaceBid(
      @NotNull UUID sosId,
      @NotNull @DecimalMin("0.01") BigDecimal price,
      @Size(max = 64) String clientMessageId) {}

  /** {@code status} is ACCEPTED or REJECTED; CANCELLED is refused by the service. */
  public record DecideBid(
      @NotNull UUID bidId, @NotNull BidStatus status, @Size(max = 64) String clientMessageId) {}

  public record WithdrawBid(@NotNull UUID bidId, @Size(max = 64) String clientMessageId) {}

  public record SyncBids(@NotNull UUID sosId, @Size(max = 64) String clientMessageId) {}

  /**
   * The one bid message that waits on Google (the distance estimate), so it does not run on the
   * shared STOMP inbound thread: the handler validates, hands the work to {@link
   * BidCommandExecutor} and returns, and the reply or error goes out from there. A full queue
   * answers 503 at once ({@link RejectedExecutionException}).
   */
  @MessageMapping("/bids")
  public void place(@Valid @Payload PlaceBid in, Authentication caller) {
    UUID mechanicId = requireRole(caller, GatewayIdentityFilter.ROLE_MECHANIC, "place a bid");
    String user = caller.getName();
    bidCommandExecutor.execute(
        () -> {
          try {
            BidSosResponse bid =
                bidService.create(new CreateBidRequest(mechanicId, in.sosId(), in.price()));
            messagingTemplate.convertAndSendToUser(
                user, "/queue/bid-results", result("BID_PLACED", in.clientMessageId(), bid));
          } catch (Exception ex) {
            messagingTemplate.convertAndSendToUser(
                user, "/queue/errors", errorFrame(ex, in.clientMessageId()));
          }
        });
  }

  @MessageMapping("/bids/decision")
  @SendToUser(destinations = "/queue/bid-results", broadcast = false)
  public Map<String, Object> decide(@Valid @Payload DecideBid in, Authentication caller) {
    UUID userId = requireRole(caller, GatewayIdentityFilter.ROLE_USER, "respond to a bid");
    BidSosResponse bid =
        bidService.updateStatus(in.bidId(), new UpdateBidStatusRequest(userId, in.status()));
    return result("BID_DECIDED", in.clientMessageId(), bid);
  }

  @MessageMapping("/bids/cancel")
  @SendToUser(destinations = "/queue/bid-results", broadcast = false)
  public Map<String, Object> withdraw(@Valid @Payload WithdrawBid in, Authentication caller) {
    UUID mechanicId = requireRole(caller, GatewayIdentityFilter.ROLE_MECHANIC, "withdraw a bid");
    return result("BID_WITHDRAWN", in.clientMessageId(), bidService.cancel(in.bidId(), mechanicId));
  }

  /**
   * Current bids on one SOS, for a client that reconnected and missed frames. The service already
   * limits a mechanic to their own bid, so the owner sees all and a bidder sees only theirs.
   */
  @MessageMapping("/bids/sync")
  @SendToUser(destinations = "/queue/bid-results", broadcast = false)
  public Map<String, Object> sync(@Valid @Payload SyncBids in, Authentication caller) {
    UUID callerId = (UUID) caller.getPrincipal();
    List<BidSosResponse> bids = bidService.getBySosRequest(in.sosId(), null, callerId);
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("type", "BIDS_SNAPSHOT");
    reply.put("sosId", in.sosId());
    reply.put("clientMessageId", in.clientMessageId());
    reply.put("bids", bids);
    return reply;
  }

  /**
   * Without this a rejected frame closes the socket and the client reconnect-loops through a
   * failure it cannot see. One error frame on the caller's own queue instead, with the REST status
   * the same failure gives and the id of the request that caused it.
   */
  @MessageExceptionHandler
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public Map<String, Object> handle(Exception ex, Message<?> message) {
    return errorFrame(ex, clientMessageId(message));
  }

  private static Map<String, Object> errorFrame(Exception ex, String clientMessageId) {
    int status = statusOf(ex);
    if (status >= 500 && status != 503) {
      log.error("Socket bid message failed", ex);
    } else {
      log.warn("Rejected socket bid message: {}", ex.getMessage());
    }
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("error", status == 500 ? "Internal server error" : safeMessage(ex));
    reply.put("status", status);
    reply.put("clientMessageId", clientMessageId);
    return reply;
  }

  private static UUID requireRole(Authentication caller, String role, String action) {
    // The socket's proven identity, never a field in the frame.
    boolean allowed = caller.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    if (!allowed) {
      throw new AccessDeniedException("Not allowed to " + action);
    }
    return (UUID) caller.getPrincipal();
  }

  private static Map<String, Object> result(
      String type, String clientMessageId, BidSosResponse bid) {
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("type", type);
    reply.put("clientMessageId", clientMessageId);
    reply.put("bid", bid);
    return reply;
  }

  private static int statusOf(Exception ex) {
    if (ex instanceof EntityNotFoundException) return 404;
    if (ex instanceof AccessDeniedException) return 403;
    if (ex instanceof IllegalStateException) return 409;
    if (ex instanceof IllegalArgumentException) return 400;
    // The rest mirror GlobalExceptionHandler, so a client sees the same status over either path.
    if (ex instanceof DataIntegrityViolationException) return 409;
    if (ex instanceof ConstraintViolationException) return 400;
    if (ex instanceof RejectedExecutionException
        || ex instanceof ServiceUnavailableException
        || ex instanceof CannotCreateTransactionException
        || ex instanceof DataAccessResourceFailureException) {
      return 503;
    }
    if (ex instanceof TooManyRequestsException) return 429;
    // Bean validation and unreadable JSON arrive as messaging exceptions: a client mistake.
    String name = ex.getClass().getSimpleName();
    if (name.equals("MethodArgumentNotValidException")
        || name.equals("MessageConversionException")
        || name.equals("MethodArgumentResolutionException")) {
      return 400;
    }
    return 500;
  }

  /**
   * Same fixed texts as GlobalExceptionHandler where it has them; nothing that echoes input or
   * class names.
   */
  private static String safeMessage(Exception ex) {
    if (ex instanceof DataIntegrityViolationException) {
      return "The request conflicts with existing data";
    }
    if (ex instanceof CannotCreateTransactionException
        || ex instanceof DataAccessResourceFailureException) {
      return "The service is busy, please retry shortly";
    }
    if (ex instanceof RejectedExecutionException) {
      return "Too many bids in progress, retry shortly";
    }
    return switch (statusOf(ex)) {
      case 403 -> "You are not allowed to perform this action";
      case 400 ->
          ex instanceof IllegalArgumentException && ex.getMessage() != null
              ? ex.getMessage()
              : "Invalid message";
      default -> ex.getMessage() == null ? "Rejected" : ex.getMessage();
    };
  }

  /** Best effort: the frame may be the thing that was malformed. */
  private static String clientMessageId(Message<?> message) {
    try {
      if (message.getPayload() instanceof byte[] raw) {
        JsonNode id = JSON.readTree(new String(raw, StandardCharsets.UTF_8)).get("clientMessageId");
        if (id != null && id.isString() && id.asString().length() <= 64) return id.asString();
      }
    } catch (RuntimeException ignored) {
      // not JSON; the reply simply carries no id
    }
    return null;
  }
}
