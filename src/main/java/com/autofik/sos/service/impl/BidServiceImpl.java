package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.BidService;
import com.autofik.sos.service.GoogleMapsService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    @Override
    @Transactional
    public BidSosResponse create(CreateBidRequest request) {
        SosRequest sosRequest = sosRequestRepository.findById(request.sosId())
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + request.sosId()));
        if (sosRequest.getStatus() != SosStatus.PENDING) {
            throw new IllegalStateException("Bids can only be placed on PENDING requests");
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
                .note(request.note())
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

        return bidSosMapper.toResponse(bidSosRepository.save(bidBuilder.build()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getBySosRequest(UUID sosId) {
        return bidSosMapper.toResponseList(bidSosRepository.findBySosRequestSosId(sosId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BidSosResponse> getByMechanic(UUID mechanicId) {
        return bidSosMapper.toResponseList(bidSosRepository.findByMechanicMechanicId(mechanicId));
    }

    @Override
    @Transactional
    public BidSosResponse updateStatus(UUID bidId, UpdateBidStatusRequest request) {
        BidSos bid = bidSosRepository.findById(bidId)
                .orElseThrow(() -> new EntityNotFoundException("Bid not found: " + bidId));

        bid.setStatus(request.status());
        bid.setRespondedAt(LocalDateTime.now());
        bidSosRepository.save(bid);

        if (request.status() == BidStatus.ACCEPTED) {
            autoRejectOtherBids(bid);
            SosRequest sos = bid.getSosRequest();
            sos.setStatus(SosStatus.ACCEPTED);
            sosRequestRepository.save(sos);
        }

        return bidSosMapper.toResponse(bid);
    }

    private void autoRejectOtherBids(BidSos acceptedBid) {
        UUID sosId = acceptedBid.getSosRequest().getSosId();
        List<BidSos> pendingBids = bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING);
        pendingBids.forEach(b -> b.setStatus(BidStatus.REJECTED));
        bidSosRepository.saveAll(pendingBids);
    }
}
