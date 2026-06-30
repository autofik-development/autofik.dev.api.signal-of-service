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
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.BidService;
import com.autofik.sos.service.GoogleMapsService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
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

    @Override
    @Transactional
    public BidSosResponse create(CreateBidRequest request) {
        SosRequest sosRequest = sosRequestRepository.findById(request.sosId())
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + request.sosId()));
        if (sosRequest.getStatus() != SosStatus.PENDING) {
            throw new IllegalStateException("Bids can only be placed on PENDING requests");
        }
        if (sosRequest.getBiddingExpiresAt() != null &&
                java.time.LocalDateTime.now().isAfter(sosRequest.getBiddingExpiresAt())) {
            throw new IllegalStateException("Bidding window has closed for this SOS request");
        }
        Mechanic mechanic = mechanicRepository.findById(request.mechanicId())
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));
        if (!Boolean.TRUE.equals(mechanic.getIsAvailable())) {
            throw new IllegalStateException("Mechanic is not available: " + request.mechanicId());
        }
        if (bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(request.sosId(), request.mechanicId())) {
            throw new IllegalStateException("Mechanic already submitted a bid on this SOS request");
        }

        BidSos.BidSosBuilder bidBuilder = BidSos.builder()
                .price(request.price())
                .sosRequest(sosRequest)
                .mechanic(mechanic);

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

        BidSos savedBid = bidSosRepository.save(bidBuilder.build());

        sosEventProducer.publishBidCreated(new BidCreatedEvent(
                savedBid.getBidId(), sosRequest.getSosId(), mechanic.getMechanicId(),
                savedBid.getPrice(),
                savedBid.getEstimatedDistanceM(), savedBid.getEstimatedDurationS(),
                savedBid.getEstimatedDistanceText(), savedBid.getEstimatedDurationText(),
                savedBid.getBidAt()
        ));
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "BID_RECEIVED", sosRequest.getUser().getUserId(), "USER", sosRequest.getSosId(),
                "A mechanic placed a bid", "Check the new bid on your request"
        ));

        return bidSosMapper.toResponse(savedBid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getBySosRequest(UUID sosId, BidSortFilter sortBy) {
        List<BidSosResponse> bids = bidSosMapper.toResponseList(bidSosRepository.findBySosRequestSosId(sosId));
        if (sortBy == null) return bids;
        return switch (sortBy) {
            case NEAREST -> bids.stream()
                    .sorted(Comparator.comparing(BidSosResponse::estimatedDistanceM,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            case CHEAPEST -> bids.stream()
                    .sorted(Comparator.comparing(BidSosResponse::price))
                    .toList();
            case TOP_RATED -> bids.stream()
                    .sorted(Comparator.comparing(
                            r -> r.mechanic() != null && r.mechanic().ratingAvg() != null
                                    ? r.mechanic().ratingAvg() : BigDecimal.ZERO,
                            Comparator.<BigDecimal>naturalOrder().reversed()))
                    .toList();
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getByMechanic(UUID mechanicId) {
        return bidSosMapper.toResponseList(bidSosRepository.findByMechanicMechanicId(mechanicId));
    }

    @Override
    @Transactional
    public BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request) {
        if (request.status() == BidStatus.CANCELLED) {
            throw new IllegalStateException("Use the cancel endpoint for mechanic-initiated cancellation");
        }

        BidSos bid = bidSosRepository.findById(bidId)
                .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

        UUID sosOwnerId = bid.getSosRequest().getUser().getUserId();
        if (!sosOwnerId.equals(request.userId())) {
            throw new IllegalStateException("User " + request.userId() + " is not authorized to update bid " + bidId);
        }

        bid.setStatus(request.status());
        bid.setRespondedAt(LocalDateTime.now());
        bidSosRepository.save(bid);

        if (request.status() == BidStatus.ACCEPTED) {
            List<BidSos> rejectedBids = autoRejectOtherBids(bid);
            SosRequest sos = bid.getSosRequest();
            sos.setStatus(SosStatus.ACCEPTED);
            sosRequestRepository.save(sos);

            Mechanic mechanic = bid.getMechanic();
            UUID userId = sos.getUser().getUserId();

            BidAcceptedEvent acceptedEvent = new BidAcceptedEvent(
                    bid.getBidId(), sos.getSosId(), mechanic.getMechanicId(), userId,
                    sos.getLocation().getY(), sos.getLocation().getX(), LocalDateTime.now()
            );
            sosEventProducer.publishBidAccepted(acceptedEvent);
            messagingTemplate.convertAndSendToUser(
                    mechanic.getMechanicId().toString(), "/queue/sos-updates", acceptedEvent
            );
            sosEventProducer.publishNotification(new SosNotificationEvent(
                    "BID_ACCEPTED", mechanic.getMechanicId(), "MECHANIC", sos.getSosId(),
                    "Your bid was accepted", "Head to the user location now"
            ));

            if (!rejectedBids.isEmpty()) {
                List<UUID> rejectedBidIds = rejectedBids.stream().map(BidSos::getBidId).toList();
                sosEventProducer.publishBidRejected(new BidRejectedEvent(sos.getSosId(), rejectedBidIds));
                rejectedBids.forEach(rejected -> sosEventProducer.publishNotification(new SosNotificationEvent(
                        "BID_REJECTED", rejected.getMechanic().getMechanicId(), "MECHANIC", sos.getSosId(),
                        "Another mechanic was selected", "Your bid was not selected for this request"
                )));
            }
        }

        return bidSosMapper.toResponse(bid);
    }

    @Override
    @Transactional
    public BidSosResponse cancel(UUID bidId, UUID mechanicId) {
        BidSos bid = bidSosRepository.findById(bidId)
                .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

        if (!bid.getMechanic().getMechanicId().equals(mechanicId)) {
            throw new IllegalStateException("Mechanic " + mechanicId + " is not authorized to cancel bid " + bidId);
        }
        if (bid.getStatus() != BidStatus.PENDING) {
            throw new IllegalStateException("Only PENDING bids can be cancelled, current status: " + bid.getStatus());
        }

        bid.setStatus(BidStatus.CANCELLED);
        bid.setRespondedAt(LocalDateTime.now());
        bidSosRepository.save(bid);

        SosRequest sos = bid.getSosRequest();
        sosEventProducer.publishBidCancelled(new BidCancelledEvent(
                bid.getBidId(), sos.getSosId(), mechanicId, bid.getRespondedAt()
        ));
        sosEventProducer.publishNotification(new SosNotificationEvent(
                "BID_CANCELLED", sos.getUser().getUserId(), "USER", sos.getSosId(),
                "A bid was withdrawn", "A mechanic withdrew their bid on your request"
        ));

        return bidSosMapper.toResponse(bid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getAll() {
        return bidSosMapper.toResponseList(bidSosRepository.findAll());
    }

    private List<BidSos> autoRejectOtherBids(BidSos acceptedBid) {
        UUID sosId = acceptedBid.getSosRequest().getSosId();
        List<BidSos> pendingBids = bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING);
        pendingBids.forEach(b -> b.setStatus(BidStatus.REJECTED));
        bidSosRepository.saveAll(pendingBids);
        return pendingBids;
    }
}
