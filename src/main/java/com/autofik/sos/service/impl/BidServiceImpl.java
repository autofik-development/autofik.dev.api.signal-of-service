package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
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
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.BidService;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.util.TransactionHooks;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidServiceImpl implements BidService {

    private final BidSosRepository bidSosRepository;
    private final SosRequestRepository sosRequestRepository;
    private final MechanicRepository mechanicRepository;
    private final BidSosMapper bidSosMapper;
    private final GoogleMapsService googleMapsService;
    private final SosEventProducer sosEventProducer;
    private final SimpMessagingTemplate messagingTemplate;


    /**
     * A mechanic offers a price on an open request.
     */
    @Override
    @Transactional
    public BidSosResponse create(CreateBidRequest request) {
        SosRequest sosRequest = sosRequestRepository.findById(request.sosId())
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + request.sosId()));

        if (sosRequest.getStatus() != SosStatus.PENDING) {
            throw new ConflictException("Bids can only be placed on PENDING requests");
        }
        if (sosRequest.getBiddingExpiresAt() != null
                && LocalDateTime.now().isAfter(sosRequest.getBiddingExpiresAt())) {
            throw new ConflictException("Bidding window has closed for this SOS request");
        }

        Mechanic mechanic = mechanicRepository.findById(request.mechanicId())
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));
        if (!Boolean.TRUE.equals(mechanic.getAvailableStatus())) {
            throw new ConflictException("Mechanic is not available: " + request.mechanicId());
        }
        if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(request.sosId(), request.mechanicId())) {
            throw new ConflictException("Mechanic already submitted a bid on this SOS request");
        }

        BidSos.BidSosBuilder bidBuilder = BidSos.builder()
                .price(request.price())
                .sosRequest(sosRequest)
                .mechanic(mechanic);

        // Road distance and ETA, not straight-line — a bid the driver compares against others is
        // only useful if it reflects the drive. Fails soft: no location, no route, no estimate.
        if (mechanic.getCurrentLocation() != null) {
            DistanceInfo info = googleMapsService.getDistanceInfo(
                    mechanic.getCurrentLocation(), sosRequest.getLocation());
            if (info != null) {
                bidBuilder
                        .estimatedDistanceM(info.distanceMeters())
                        .estimatedDurationS(info.durationSeconds())
                        .estimatedDistanceText(info.distanceText())
                        .estimatedDurationText(info.durationText());
            }
        }

        BidSos savedBid;
        try {
            // Flushed here rather than at commit so the unique index answers inside this try. The
            // check above loses to a double-tap — both requests read "no bid yet" before either
            // inserts — and the constraint is what actually holds. Same error either way, so the
            // client cannot tell which one caught it.
            savedBid = bidSosRepository.saveAndFlush(bidBuilder.build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Mechanic already submitted a bid on this SOS request", e);
        }

        BidSosResponse response = bidSosMapper.toResponse(savedBid);
        UUID userId = sosRequest.getUser().getUserId();
        UUID sosId = sosRequest.getSosId();
        TransactionHooks.afterCommit(() -> {
            sosEventProducer.publishBidCreated(new BidCreatedEvent(
                    savedBid.getBidId(), sosId, mechanic.getMechanicId(),
                    savedBid.getPrice(),
                    savedBid.getEstimatedDistanceM(), savedBid.getEstimatedDurationS(),
                    savedBid.getEstimatedDistanceText(), savedBid.getEstimatedDurationText(),
                    savedBid.getBidAt()
            ));
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "BID_RECEIVED", userId, "USER", sosId,
                    "A mechanic placed a bid", "Check the new bid on your request"
            ));
            // The bidding window is 250 seconds. Without this the driver's list only changed when
            // they pulled to refresh, so the push notification was telling them about a bid the
            // screen in front of them could not show. Sent on the driver's private queue, not a
            // topic — a per-SOS broadcast would hand every bidder their competitors' prices.
            messagingTemplate.convertAndSendToUser(
                    userId.toString(), "/queue/sos-updates",
                    Map.of("type", "BID_RECEIVED", "sosId", sosId, "bid", response)
            );
        });

        return response;
    }

    // Sorting is done in-memory because the fields (estimatedDistanceM, ratingAvg) span
    // multiple tables and in-DB sorting would require additional joins or subqueries.
    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getBySosRequest(UUID sosId, BidSortFilter sortBy, UUID callerId) {
        SosRequest sos = sosRequestRepository.findById(sosId)
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + sosId));

        List<BidSos> found = bidSosRepository.findBySosRequestSosId(sosId);
        // A mechanic asking about someone else's SOS gets their own bid and nothing more; the full
        // list would hand them every rival's price. Non-participants see an empty list rather than
        // a 403, which would confirm the sosId is real.
        if (!sos.getUser().getUserId().equals(callerId)) {
            found = found.stream()
                    .filter(bid -> bid.getMechanic().getMechanicId().equals(callerId))
                    .toList();
        }

        List<BidSosResponse> bids = bidSosMapper.toResponseList(found);
        if (sortBy == null) return bids;

        return switch (sortBy) {
            case NEAREST -> bids.stream()
                    .sorted(Comparator.comparing(BidSosResponse::estimatedDistanceM,
                            Comparator.nullsLast(Comparator.naturalOrder()))) // bids with no estimate sort last
                    .toList();
            case CHEAPEST -> bids.stream()
                    .sorted(Comparator.comparing(BidSosResponse::price))
                    .toList();
            case TOP_RATED -> bids.stream()
                    .sorted(Comparator.comparing(
                            r -> r.mechanic() != null && r.mechanic().avgRate() != null
                                    ? r.mechanic().avgRate() : BigDecimal.ZERO,
                            Comparator.<BigDecimal>naturalOrder().reversed()))
                    .toList();
        };
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BidSosResponse> getByMechanic(UUID mechanicId, Pageable pageable) {
        return bidSosRepository.findByMechanicMechanicId(mechanicId, pageable)
                .map(bidSosMapper::toResponse);
    }

    /**
     * The driver accepts or rejects one bid. Accepting also assigns the mechanic, closes the
     * auction, and rejects everyone else.
     */
    @Override
    @Transactional
    public BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request) {
        if (request.status() == BidStatus.CANCELLED) {
            throw new IllegalArgumentException("Use the cancel endpoint for mechanic-initiated cancellation");
        }

        BidSos bid = bidSosRepository.findById(bidId)
                .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

        UUID sosOwnerId = bid.getSosRequest().getUser().getUserId();
        if (!sosOwnerId.equals(request.userId())) {
            throw new AccessDeniedException("You can only respond to bids on your own SOS request");
        }

        // Without this, a driver who accepts a second bid ends up with two ACCEPTED bids on one
        // request: autoRejectOtherBids only touches PENDING rows, so the first winner stays
        // accepted. Every downstream lookup takes the first ACCEPTED bid it finds, so the assigned
        // mechanic, the arrival check and location visibility would each resolve to whichever row
        // the database happened to return.
        if (bid.getStatus() != BidStatus.PENDING) {
            throw new ConflictException("This bid has already been answered: " + bid.getStatus());
        }

        SosRequest sos = bid.getSosRequest();
        // The auction is over once the request leaves PENDING — expired, cancelled, or already
        // assigned. Accepting from a stale screen would otherwise drag a dead request back to life.
        if (sos.getStatus() != SosStatus.PENDING) {
            throw new ConflictException(
                    "Bids can no longer be answered on a request with status: " + sos.getStatus());
        }

        bid.setStatus(request.status());
        bid.setRespondedAt(LocalDateTime.now());
        bidSosRepository.save(bid);

        if (request.status() == BidStatus.ACCEPTED) {
            // Everyone else is free to take other work.
            List<BidSos> rejectedBids = autoRejectOtherBids(bid);

            // Same state machine SosServiceImpl uses. The request was PENDING when checked above,
            // so this holds today — it is here so a future state cannot quietly become acceptable.
            if (!sos.getStatus().canTransitionTo(SosStatus.ACCEPTED)) {
                throw new ConflictException(
                        "Invalid status transition: " + sos.getStatus() + " → " + SosStatus.ACCEPTED);
            }
            sos.setStatus(SosStatus.ACCEPTED);
            sosRequestRepository.save(sos);

            Mechanic mechanic = bid.getMechanic();
            UUID userId = sos.getUser().getUserId();
            UUID sosId = sos.getSosId();

            BidAcceptedEvent acceptedEvent = new BidAcceptedEvent(
                    bid.getBidId(), sosId, mechanic.getMechanicId(), userId,
                    sos.getLocation().getY(), sos.getLocation().getX(), LocalDateTime.now()
            );
            List<UUID> rejectedBidIds = rejectedBids.stream().map(BidSos::getBidId).toList();
            List<UUID> rejectedMechanicIds = rejectedBids.stream()
                    .map(rejected -> rejected.getMechanic().getMechanicId()).toList();

            TransactionHooks.afterCommit(() -> {
                sosEventProducer.publishBidAccepted(acceptedEvent);
                // Straight to the winning mechanic's screen so their map starts routing.
                messagingTemplate.convertAndSendToUser(
                        mechanic.getMechanicId().toString(), "/queue/sos-updates", acceptedEvent
                );
                sosEventProducer.publishNotification(new SosNotificationEvent(
                        "BID_ACCEPTED", mechanic.getMechanicId(), "MECHANIC", sosId,
                        "Your bid was accepted", "Head to the user location now"
                ));

                if (rejectedBidIds.isEmpty()) return;
                sosEventProducer.publishBidRejected(new BidRejectedEvent(sosId, rejectedBidIds));
                for (int i = 0; i < rejectedBidIds.size(); i++) {
                    UUID rejectedMechanicId = rejectedMechanicIds.get(i);
                    sosEventProducer.publishNotification(new SosNotificationEvent(
                            "BID_REJECTED", rejectedMechanicId, "MECHANIC", sosId,
                            "Another mechanic was selected", "Your bid was not selected for this request"
                    ));
                    // The winner already gets a socket message; the losers only got a push, so their
                    // open bid screen kept showing a live auction that had already been decided.
                    // Their own bid id and nothing else — the winning price is not theirs to see.
                    messagingTemplate.convertAndSendToUser(
                            rejectedMechanicId.toString(), "/queue/sos-updates",
                            Map.of("type", "BID_REJECTED", "sosId", sosId,
                                    "bidId", rejectedBidIds.get(i))
                    );
                }
            });
        }

        return bidSosMapper.toResponse(bid);
    }

    /**
     * A mechanic withdraws their own bid before the driver has answered it.
     */
    @Override
    @Transactional
    public BidSosResponse cancel(UUID bidId, UUID mechanicId) {
        BidSos bid = bidSosRepository.findById(bidId)
                .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

        if (!bid.getMechanic().getMechanicId().equals(mechanicId)) {
            throw new AccessDeniedException("You can only cancel your own bid");
        }
        if (bid.getStatus() != BidStatus.PENDING) {
            throw new ConflictException("Only PENDING bids can be cancelled, current status: " + bid.getStatus());
        }

        bid.setStatus(BidStatus.CANCELLED);
        bid.setRespondedAt(LocalDateTime.now());
        bidSosRepository.save(bid);

        SosRequest sos = bid.getSosRequest();
        UUID sosId = sos.getSosId();
        UUID userId = sos.getUser().getUserId();
        LocalDateTime respondedAt = bid.getRespondedAt();
        UUID cancelledBidId = bid.getBidId();

        TransactionHooks.afterCommit(() -> {
            sosEventProducer.publishBidCancelled(new BidCancelledEvent(
                    cancelledBidId, sosId, mechanicId, respondedAt
            ));
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "BID_CANCELLED", userId, "USER", sosId,
                    "A bid was withdrawn", "A mechanic withdrew their bid on your request"
            ));
            // Take the withdrawn bid off the driver's screen. Accepting a bid that no longer exists
            // wastes the one resource nobody has during a breakdown, which is time.
            messagingTemplate.convertAndSendToUser(
                    userId.toString(), "/queue/sos-updates",
                    Map.of("type", "BID_CANCELLED", "sosId", sosId, "bidId", cancelledBidId)
            );
        });

        return bidSosMapper.toResponse(bid);
    }

    // When the user accepts one bid, all other PENDING bids on the same SOS must be
    // rejected immediately so those mechanics know they're free to take other jobs.
    private List<BidSos> autoRejectOtherBids(BidSos acceptedBid) {
        UUID sosId = acceptedBid.getSosRequest().getSosId();
        List<BidSos> pendingBids = bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING);
        pendingBids.forEach(b -> b.setStatus(BidStatus.REJECTED));
        bidSosRepository.saveAll(pendingBids);
        return pendingBids;
    }
}
