package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidSortFilter;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.BidAcceptedEvent;
import com.autofik.sos.dto.event.BidCancelledEvent;
import com.autofik.sos.dto.event.BidCreatedEvent;
import com.autofik.sos.dto.event.BidRejectedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.service.BidService;
import com.autofik.sos.service.GoogleMapsService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service // tells spring this class core business logic execution
@RequiredArgsConstructor // automatic creates a constructor with all dependencies
public class BidServiceImpl implements BidService {

  // --- DEPENDENCIES (REPOSITORIES & EXTERNAL SERVICES) ---
  private final BidSosRepository bidSosRepository;
  private final SosRequestRepository sosRequestRepository;
  private final SosStatusHistoryRepository sosStatusHistoryRepository;
  private final MechanicRepository mechanicRepository;
  private final BidSosMapper bidSosMapper; // Converts between Database Entities and DTO Responses
  private final GoogleMapsService googleMapsService; // Calculates real road mileage and times
  private final SosEventProducer sosEventProducer; // Publishes asynchronous system events to Kafka
  private final TransactionTemplate transactionTemplate;
  private final SimpMessagingTemplate
      messagingTemplate; // Handles instant WebSocket communication directly to mobile apps

  /** Start and end of the route a bid's estimate is computed for; {@code from} may be unknown. */
  private record Route(Point from, Point to) {}

  /**
   * WORKFLOW: A mechanic submits a price bid to help a stranded driver.
   *
   * <p>Deliberately NOT @Transactional. The Google Distance Matrix call can take seconds, and
   * inside one transaction it held a Postgres connection and a shared STOMP inbound thread for that
   * whole time, so a burst of bids could starve live location pings. Only the two database phases
   * run in a transaction, as in SosServiceImpl.create.
   */
  @Override
  public BidSosResponse create(CreateBidRequest request) {
    Route route = transactionTemplate.execute(status -> checkBidAllowed(request));

    // External call, outside any transaction.
    DistanceInfo info =
        route.from() == null ? null : googleMapsService.getDistanceInfo(route.from(), route.to());

    return transactionTemplate.execute(status -> persistBid(request, info));
  }

  // Runs inside the transaction opened by create(); keep external calls out of it.
  private Route checkBidAllowed(CreateBidRequest request) {
    SosRequest sosRequest = loadOpenSos(request.sosId());
    Mechanic mechanic = loadAvailableMechanic(request.mechanicId());
    if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(
        request.sosId(), request.mechanicId())) {
      throw new IllegalStateException("Mechanic already submitted a bid on this SOS request");
    }
    return new Route(mechanic.getCurrentLocation(), sosRequest.getLocation());
  }

  private SosRequest loadOpenSos(UUID sosId) {
    SosRequest sosRequest =
        sosRequestRepository
            .findById(sosId)
            .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));
    // Bids can only be submitted if the ticket is still open and waiting (PENDING)
    if (sosRequest.getStatus() != SosStatus.PENDING) {
      throw new IllegalStateException("Bids can only be placed on PENDING requests");
    }
    // ...and the timeline window to place bids has not expired
    if (sosRequest.getBiddingExpiresAt() != null
        && LocalDateTime.now().isAfter(sosRequest.getBiddingExpiresAt())) {
      throw new IllegalStateException("Bidding window has closed for this SOS request");
    }
    return sosRequest;
  }

  private Mechanic loadAvailableMechanic(UUID mechanicId) {
    Mechanic mechanic =
        mechanicRepository
            .findById(mechanicId)
            .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + mechanicId));
    if (!Boolean.TRUE.equals(mechanic.getAvailableStatus())) {
      throw new IllegalStateException("Mechanic is not available: " + mechanicId);
    }
    return mechanic;
  }

  // Runs inside the transaction opened by create(). The first phase's checks are repeated because
  // the Maps call between the two phases can take a while and the ticket may have moved on.
  private BidSosResponse persistBid(CreateBidRequest request, DistanceInfo info) {
    SosRequest sosRequest = loadOpenSos(request.sosId());
    Mechanic mechanic = loadAvailableMechanic(request.mechanicId());

    BidSos.BidSosBuilder bidBuilder =
        BidSos.builder().price(request.price()).sosRequest(sosRequest).mechanic(mechanic);
    if (info != null) {
      bidBuilder
          .estimatedDistanceM(info.distanceMeters())
          .estimatedDurationS(info.durationSeconds())
          .estimatedDistanceText(info.distanceText())
          .estimatedDurationText(info.durationText());
    }

    // RACE GUARD: lock the ticket and re-check against the database: a driver accepting another
    // bid meanwhile either committed first (we see it and refuse) or waits until this bid is in,
    // so their auto-reject sees it. Re-checking the duplicate here also closes a mechanic's
    // double-tap.
    if (!SosStatus.PENDING
        .name()
        .equals(sosRequestRepository.lockStatus(request.sosId()).orElse(null))) {
      throw new IllegalStateException("Bids can only be placed on PENDING requests");
    }
    if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(
        request.sosId(), request.mechanicId())) {
      throw new IllegalStateException("Mechanic already submitted a bid on this SOS request");
    }

    // Save the compiled Bid object into the PostgreSQL database table
    BidSos savedBid = bidSosRepository.save(bidBuilder.build());

    // KAFKA FAN-OUT: Publish the creation event to sync down other microservices
    sosEventProducer.publishBidCreated(
        new BidCreatedEvent(
            savedBid.getBidId(),
            sosRequest.getSosId(),
            mechanic.getMechanicId(),
            savedBid.getPrice(),
            savedBid.getEstimatedDistanceM(),
            savedBid.getEstimatedDurationS(),
            savedBid.getEstimatedDistanceText(),
            savedBid.getEstimatedDurationText(),
            savedBid.getBidAt()));

    // PUSH NOTIFICATION: Alert the user/driver's device immediately that a new quote has arrived
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "BID_RECEIVED",
            sosRequest.getUser().getUserId(),
            "USER",
            sosRequest.getSosId(),
            "A mechanic placed a bid",
            "Check the new bid on your request"));

    BidSosResponse response = bidSosMapper.toResponse(savedBid);

    // The bidding window is 60 seconds. Without this the driver's list only changed when they
    // pulled to refresh, so the push notification was telling them about a bid the screen in
    // front of them could not show. Sent on the driver's private queue, not a topic — a
    // per-SOS broadcast would hand every bidder their competitors' prices.
    messagingTemplate.convertAndSendToUser(
        sosRequest.getUser().getUserId().toString(),
        "/queue/sos-updates",
        Map.of("type", "BID_RECEIVED", "sosId", sosRequest.getSosId(), "bid", response));

    return response;
  }

  /**
   * WORKFLOW: Returns a list of all bids submitted for a specific SOS ticket, sorted by filter
   * type.
   */
  // Sorting is done in-memory because the fields (estimatedDistanceM, ratingAvg) span
  // multiple tables and in-DB sorting would require additional joins or subqueries.
  @Override
  @Transactional(
      readOnly = true) // Optimization: Tells the DB this is a read-only query (no data writes)
  public List<BidSosResponse> getBySosRequest(UUID sosId, BidSortFilter sortBy, UUID callerId) {
    SosRequest sos =
        sosRequestRepository
            .findById(sosId)
            .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));

    List<BidSos> found = bidSosRepository.findBySosRequestSosId(sosId);
    // A mechanic asking about someone else's SOS gets their own bid and nothing more; the full
    // list would hand them every rival's price. Non-participants see an empty list rather than
    // a 403, which would confirm the sosId is real.
    if (!sos.getUser().getUserId().equals(callerId)) {
      found =
          found.stream().filter(bid -> bid.getMechanic().getMechanicId().equals(callerId)).toList();
    }

    List<BidSosResponse> bids = bidSosMapper.toResponseList(found);
    if (sortBy == null) return bids;

    // Apply in-memory Stream sorting based on the user's selected preference tab
    return switch (sortBy) {
      case NEAREST ->
          bids.stream()
              .sorted(
                  Comparator.comparing(
                      BidSosResponse::estimatedDistanceM,
                      Comparator.nullsLast(
                          Comparator.naturalOrder()))) // Nearest meters first, empty metrics last
              .toList();
      case CHEAPEST ->
          bids.stream()
              .sorted(Comparator.comparing(BidSosResponse::price)) // Lowest price tag first
              .toList();
      case TOP_RATED ->
          bids.stream()
              .sorted(
                  Comparator.comparing(
                      r ->
                          r.mechanic() != null && r.mechanic().avgRate() != null
                              ? r.mechanic().avgRate()
                              : BigDecimal.ZERO,
                      Comparator.<BigDecimal>naturalOrder().reversed()))
              .toList();
    };
  }

  @Override
  @Transactional(readOnly = true)
  public Page<BidSosResponse> getByMechanic(UUID mechanicId, Pageable pageable) {
    return bidSosRepository
        .findByMechanicMechanicId(mechanicId, pageable)
        .map(bidSosMapper::toResponse);
  }

  /** WORKFLOW: Processes a driver's decision to ACCEPT or REJECT a mechanic's bid. */
  @Override
  @Transactional
  public BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request) {

    // Mechanics are blocked from using this endpoint to back out; they must use the cancel endpoint
    if (request.status() == BidStatus.CANCELLED) {
      // Wrong endpoint for what was asked — a malformed request (400), not a state conflict.
      throw new IllegalArgumentException(
          "Use the cancel endpoint for mechanic-initiated cancellation");
    }

    BidSos bid =
        bidSosRepository
            .findById(bidId)
            .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

    // SECURITY CHECK: Ensure that the person executing this call is the actual owner who created
    // the SOS ticket
    UUID sosOwnerId = bid.getSosRequest().getUser().getUserId();
    if (!sosOwnerId.equals(request.userId())) {
      throw new AccessDeniedException("You can only respond to bids on your own SOS request");
    }

    // RACE GUARD: lock the ticket before touching any bid. Concurrent bid placements and a second
    // accept on the same ticket now queue behind this one; the status is read fresh under the lock,
    // so whoever comes second sees ACCEPTED and is refused instead of leaving a PENDING bid behind.
    // Rejecting takes the lock too, so it cannot interleave with the mechanic withdrawing the same
    // bid (cancel takes it as well).
    String ticketStatus =
        sosRequestRepository.lockStatus(bid.getSosRequest().getSosId()).orElse(null);
    if (request.status() == BidStatus.ACCEPTED && !SosStatus.PENDING.name().equals(ticketStatus)) {
      throw new IllegalStateException("This SOS request is no longer open for accepting bids");
    }

    // A bid already responded to cannot be responded to again — without this, re-accepting an
    // ACCEPTED bid re-runs the entire domino effect below (duplicate Kafka events, duplicate
    // push notifications, duplicate WebSocket messages) on every retry or double-tap.
    // Read under the lock: the bid loaded above may have been withdrawn while this waited.
    requirePending(bid, "responded to");

    // Commit the status change (e.g., ACCEPTED) to the database
    bid.setStatus(request.status());
    bid.setRespondedAt(LocalDateTime.now());
    bidSosRepository.save(bid);

    // --- DOMINO EFFECT: IF THE BID WAS ACCEPTED ---
    if (request.status() == BidStatus.ACCEPTED) {

      // 1. AUTO-REJECT RUNNERS UP: Reject all other competing bids for this ticket so those
      // mechanics can work elsewhere
      List<BidSos> rejectedBids = autoRejectOtherBids(bid);

      // 2. TICKET STATE TRANSITION: Lock down the core SOS Request state from PENDING to ACCEPTED
      SosRequest sos = bid.getSosRequest();
      sos.setStatus(SosStatus.ACCEPTED);
      sosRequestRepository.save(sos);

      // 2b. HISTORY: record PENDING -> ACCEPTED like every other transition. The winning
      // mechanic proposed it (their bid) and the driver approved it (this accept).
      sosStatusHistoryRepository.save(
          SosStatusHistory.builder()
              .sosRequest(sos)
              .fromStatus(SosStatus.PENDING)
              .toStatus(SosStatus.ACCEPTED)
              .proposedBy(bid.getMechanic())
              .proposedAt(bid.getBidAt())
              .approvedStatus(ApprovedStatus.ACCEPTED)
              .approvedBy(sos.getUser())
              .approveAt(bid.getRespondedAt())
              .build());

      Mechanic mechanic = bid.getMechanic();
      UUID userId = sos.getUser().getUserId();

      // Structure a message payload packed with targeted GPS directions
      BidAcceptedEvent acceptedEvent =
          new BidAcceptedEvent(
              bid.getBidId(),
              sos.getSosId(),
              mechanic.getMechanicId(),
              userId,
              sos.getLocation().getY(),
              sos.getLocation().getX(),
              LocalDateTime.now());

      // 3. BROADCAST KAFKA EVENT: Broadcasts the data pipeline update system-wide
      sosEventProducer.publishBidAccepted(acceptedEvent);

      // 4. LIVE WEBSOCKET ALERT: Fires a message directly to the winning mechanic's app screen to
      // update their map routing
      // The frame had no "type" while every other sos-updates frame does, so the app could not
      // route it; same fields as the Kafka event plus the type.
      Map<String, Object> winnerFrame = new java.util.LinkedHashMap<>();
      winnerFrame.put("type", "BID_ACCEPTED");
      winnerFrame.put("bidId", acceptedEvent.bidId());
      winnerFrame.put("sosId", acceptedEvent.sosId());
      winnerFrame.put("mechanicId", acceptedEvent.mechanicId());
      winnerFrame.put("userId", acceptedEvent.userId());
      winnerFrame.put("latitude", acceptedEvent.latitude());
      winnerFrame.put("longitude", acceptedEvent.longitude());
      winnerFrame.put("acceptedAt", acceptedEvent.acceptedAt());
      messagingTemplate.convertAndSendToUser(
          mechanic.getMechanicId().toString(), "/queue/sos-updates", winnerFrame);

      // 5. PUSH NOTIFICATION (WINNER): Pop-up alert telling the chosen mechanic to hit the road
      sosEventProducer.publishNotification(
          new SosNotificationEvent(
              "BID_ACCEPTED",
              mechanic.getMechanicId(),
              "MECHANIC",
              sos.getSosId(),
              "Your bid was accepted",
              "Head to the user location now"));

      // 6. PUSH NOTIFICATIONS (LOSERS): Send polite "not selected" alerts to all the runners-up
      // mechanics
      if (!rejectedBids.isEmpty()) {
        List<UUID> rejectedBidIds = rejectedBids.stream().map(BidSos::getBidId).toList();
        sosEventProducer.publishBidRejected(new BidRejectedEvent(sos.getSosId(), rejectedBidIds));
        // One push event for every runner-up: the content is identical, so it goes out as a
        // single multi-recipient OneSignal request instead of a burst of one per loser.
        sosEventProducer.publishNotification(
            new SosNotificationEvent(
                "BID_REJECTED",
                null,
                "MECHANIC",
                sos.getSosId(),
                "Another mechanic was selected",
                "Your bid was not selected for this request",
                null,
                null,
                rejectedBids.stream().map(r -> r.getMechanic().getMechanicId()).toList()));
        rejectedBids.forEach(
            rejected -> {
              // The winner already gets a socket message; the losers only got a push, so their
              // open bid screen kept showing a live auction that had already been decided.
              // Their own bid id and nothing else — the winning price is not theirs to see.
              messagingTemplate.convertAndSendToUser(
                  rejected.getMechanic().getMechanicId().toString(),
                  "/queue/sos-updates",
                  Map.of(
                      "type",
                      "BID_REJECTED",
                      "sosId",
                      sos.getSosId(),
                      "bidId",
                      rejected.getBidId()));
            });
      }
    }

    return bidSosMapper.toResponse(bid);
  }

  /** WORKFLOW: A mechanic withdraws their bid before a driver accepts it. */
  @Override
  @Transactional
  public BidSosResponse cancel(UUID bidId, UUID mechanicId) {
    BidSos bid =
        bidSosRepository
            .findById(bidId)
            .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

    // SECURITY CHECK: Verify that the mechanic trying to cancel the bid is the actual creator of
    // the bid
    if (!bid.getMechanic().getMechanicId().equals(mechanicId)) {
      throw new AccessDeniedException("You can only cancel your own bid");
    }
    // RACE GUARD: same ticket lock the driver's accept/reject takes, so the two cannot both win.
    sosRequestRepository.lockStatus(bid.getSosRequest().getSosId());
    // SAFETY CHECK: A mechanic can only withdraw a bid if it's still pending (not yet accepted or
    // rejected), read under the lock.
    requirePending(bid, "cancelled");

    // Set status to CANCELLED in the database
    bid.setStatus(BidStatus.CANCELLED);
    bid.setRespondedAt(LocalDateTime.now());
    bidSosRepository.save(bid);

    SosRequest sos = bid.getSosRequest();

    // Notify the system ecosystem about the cancellation event via Kafka
    sosEventProducer.publishBidCancelled(
        new BidCancelledEvent(bid.getBidId(), sos.getSosId(), mechanicId, bid.getRespondedAt()));
    // PUSH NOTIFICATION: Alert the driver that a mechanic has withdrawn their quote proposal
    sosEventProducer.publishNotification(
        new SosNotificationEvent(
            "BID_CANCELLED",
            sos.getUser().getUserId(),
            "USER",
            sos.getSosId(),
            "A bid was withdrawn",
            "A mechanic withdrew their bid on your request"));

    // Take the withdrawn bid off the driver's screen. Accepting a bid that no longer exists
    // wastes the one resource nobody has during a breakdown, which is time.
    messagingTemplate.convertAndSendToUser(
        sos.getUser().getUserId().toString(),
        "/queue/sos-updates",
        Map.of("type", "BID_CANCELLED", "sosId", sos.getSosId(), "bidId", bid.getBidId()));

    return bidSosMapper.toResponse(bid);
  }

  /** Call after taking the ticket lock: refuses unless the stored status is PENDING. */
  private void requirePending(BidSos bid, String action) {
    BidStatus stored =
        bidSosRepository
            .currentStatus(bid.getBidId())
            .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bid.getBidId()));
    if (stored != BidStatus.PENDING) {
      throw new IllegalStateException(
          "Only PENDING bids can be " + action + ", current status: " + stored);
    }
    bid.setStatus(stored);
  }

  // When the user accepts one bid, all other PENDING bids on the same SOS must be
  // rejected immediately so those mechanics know they're free to take other jobs.
  private List<BidSos> autoRejectOtherBids(BidSos acceptedBid) {
    UUID sosId = acceptedBid.getSosRequest().getSosId();

    // Pull all other bids matching this SOS ID that are still stuck in a 'PENDING' status state
    List<BidSos> pendingBids =
        bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING);

    // Loop through and flip them all to REJECTED status states
    pendingBids.forEach(b -> b.setStatus(BidStatus.REJECTED));

    // Update them collectively via batch saving
    bidSosRepository.saveAll(pendingBids);
    return pendingBids;
  }
}
