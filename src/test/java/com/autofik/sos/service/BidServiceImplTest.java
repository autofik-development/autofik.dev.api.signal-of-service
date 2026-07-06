package com.autofik.sos.service;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.request.UpdateBidStatusRequest;
import com.autofik.sos.dto.event.BidAcceptedEvent;
import com.autofik.sos.dto.event.BidCancelledEvent;
import com.autofik.sos.dto.event.BidCreatedEvent;
import com.autofik.sos.dto.event.BidRejectedEvent;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.impl.BidServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidServiceImplTest {

    @Mock BidSosRepository bidSosRepository;
    @Mock SosRequestRepository sosRequestRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock BidSosMapper bidSosMapper;
    @Mock GoogleMapsService googleMapsService;
    @Mock SosEventProducer sosEventProducer;
    @Mock SimpMessagingTemplate messagingTemplate;

    @InjectMocks BidServiceImpl bidService;

    private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);

    private final UUID sosId      = UUID.randomUUID();
    private final UUID bidId      = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();
    private final UUID userId     = UUID.randomUUID();

    private User user;
    private Vehicle vehicle;
    private Mechanic mechanic;
    private SosRequest pendingSos;
    private BidSos bid;
    private BidSosResponse bidResponse;

    @BeforeEach
    void setUp() {
        user = User.builder().userId(userId).name("Alice").build();
        vehicle = Vehicle.builder().id(UUID.randomUUID()).carBrand("Honda").carModel("Civic").build();
        mechanic = Mechanic.builder().mechanicId(mechanicId).fullName("Bob").phoneNumber("081")
                .email("bob@fix.com").availableStatus(true).build();
        pendingSos = SosRequest.builder().sosId(sosId).problemType("Flat Tire")
                .status(SosStatus.PENDING).user(user).vehicle(vehicle)
                .location(GEO.createPoint(new Coordinate(100.5018, 13.7563))).build();
        bid = BidSos.builder().bidId(bidId).price(BigDecimal.valueOf(500))
                .sosRequest(pendingSos).mechanic(mechanic).build();
        bidResponse = new BidSosResponse(bidId, BigDecimal.valueOf(500), BidStatus.PENDING, null, null, null, null, null, null, null);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_validRequest_savesAndReturnsResponse() {
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.valueOf(500));
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(false);
        when(bidSosRepository.save(any(BidSos.class))).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        assertThat(bidService.create(request)).isEqualTo(bidResponse);
        verify(bidSosRepository).save(any(BidSos.class));
        verify(sosEventProducer).publishBidCreated(any(BidCreatedEvent.class));
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void create_publishesBidCreatedEventWithRequestData() {
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.valueOf(500));
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(false);
        when(bidSosRepository.save(any(BidSos.class))).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.create(request);

        ArgumentCaptor<BidCreatedEvent> captor = ArgumentCaptor.forClass(BidCreatedEvent.class);
        verify(sosEventProducer).publishBidCreated(captor.capture());
        BidCreatedEvent event = captor.getValue();
        assertThat(event.sosId()).isEqualTo(sosId);
        assertThat(event.mechanicId()).isEqualTo(mechanicId);
        assertThat(event.price()).isEqualTo(BigDecimal.valueOf(500));
    }

    @Test
    void create_sosNotPending_throwsIllegalStateException() {
        SosRequest acceptedSos = SosRequest.builder().sosId(sosId).problemType("X")
                .status(SosStatus.ACCEPTED).user(user).vehicle(vehicle).build();
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.TEN);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(acceptedSos));

        assertThatThrownBy(() -> bidService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void create_mechanicNotAvailable_throwsIllegalStateException() {
        Mechanic unavailable = Mechanic.builder().mechanicId(mechanicId).fullName("Bob").phoneNumber("081")
                .email("b@fix.com").availableStatus(false).build();
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.TEN);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(unavailable));

        assertThatThrownBy(() -> bidService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void create_duplicateBid_throwsIllegalStateException() {
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.TEN);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(true);

        assertThatThrownBy(() -> bidService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already submitted");
    }

    @Test
    void create_mechanicHasCurrentLocation_enrichesBidWithDistanceInfo() {
        Point mechanicLocation = GEO.createPoint(new Coordinate(100.50, 13.75));
        Point sosLocation = GEO.createPoint(new Coordinate(100.55, 13.80));
        Mechanic mechanicWithLocation = Mechanic.builder().mechanicId(mechanicId).fullName("Bob").phoneNumber("081")
                .email("bob@fix.com").availableStatus(true)
                .currentLocation(mechanicLocation).build();
        SosRequest sosWithLocation = SosRequest.builder().sosId(sosId).problemType("Flat Tire")
                .status(SosStatus.PENDING).user(user).vehicle(vehicle).location(sosLocation).build();

        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.valueOf(500));
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosWithLocation));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanicWithLocation));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(false);
        when(googleMapsService.getDistanceInfo(mechanicLocation, sosLocation))
                .thenReturn(new DistanceInfo(3200, 480, "3.2 km", "8 mins"));
        when(bidSosRepository.save(any(BidSos.class))).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.create(request);

        ArgumentCaptor<BidSos> captor = ArgumentCaptor.forClass(BidSos.class);
        verify(bidSosRepository).save(captor.capture());
        BidSos saved = captor.getValue();
        assertThat(saved.getEstimatedDistanceM()).isEqualTo(3200);
        assertThat(saved.getEstimatedDurationS()).isEqualTo(480);
        assertThat(saved.getEstimatedDistanceText()).isEqualTo("3.2 km");
        assertThat(saved.getEstimatedDurationText()).isEqualTo("8 mins");
    }

    @Test
    void create_mechanicHasCurrentLocationButMapsReturnsNull_bidStillSaved() {
        Point mechanicLocation = GEO.createPoint(new Coordinate(100.50, 13.75));
        Point sosLocation = GEO.createPoint(new Coordinate(100.55, 13.80));
        Mechanic mechanicWithLocation = Mechanic.builder().mechanicId(mechanicId).fullName("Bob").phoneNumber("081")
                .email("bob@fix.com").availableStatus(true)
                .currentLocation(mechanicLocation).build();
        SosRequest sosWithLocation = SosRequest.builder().sosId(sosId).problemType("Flat Tire")
                .status(SosStatus.PENDING).user(user).vehicle(vehicle).location(sosLocation).build();

        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.valueOf(500));
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sosWithLocation));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanicWithLocation));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(false);
        when(googleMapsService.getDistanceInfo(any(), any())).thenReturn(null);
        when(bidSosRepository.save(any(BidSos.class))).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.create(request);

        ArgumentCaptor<BidSos> captor = ArgumentCaptor.forClass(BidSos.class);
        verify(bidSosRepository).save(captor.capture());
        BidSos saved = captor.getValue();
        assertThat(saved.getEstimatedDistanceM()).isNull();
        assertThat(saved.getEstimatedDurationS()).isNull();
    }

    @Test
    void create_mechanicHasNoCurrentLocation_distanceNotQueried() {
        CreateBidRequest request = new CreateBidRequest(mechanicId, sosId, BigDecimal.valueOf(500));
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId)).thenReturn(false);
        when(bidSosRepository.save(any(BidSos.class))).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.create(request);

        verify(googleMapsService, never()).getDistanceInfo(any(), any());
    }

    // ── getBySosRequest / getByMechanic ───────────────────────────────────────

    @Test
    void getBySosRequest_delegatesAndReturnsMappedList() {
        when(bidSosRepository.findBySosRequestSosId(sosId)).thenReturn(List.of(bid));
        when(bidSosMapper.toResponseList(List.of(bid))).thenReturn(List.of(bidResponse));

        assertThat(bidService.getBySosRequest(sosId, null)).containsExactly(bidResponse);
    }

    @Test
    void getByMechanic_delegatesAndReturnsMappedList() {
        Pageable pageable = PageRequest.of(0, 20);
        when(bidSosRepository.findByMechanicMechanicId(mechanicId, pageable))
                .thenReturn(new PageImpl<>(List.of(bid)));
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        Page<BidSosResponse> result = bidService.getByMechanic(mechanicId, pageable);
        assertThat(result.getContent()).containsExactly(bidResponse);
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    void updateStatus_rejected_setsStatusAndRespondedAt() {
        UpdateBidStatusRequest request = new UpdateBidStatusRequest(userId, BidStatus.REJECTED);
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidSosRepository.save(bid)).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.updateStatus(bidId, request);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(bid.getRespondedAt()).isNotNull();
        verify(bidSosRepository, never()).saveAll(anyList());
        verify(sosRequestRepository, never()).save(any());
        verify(sosEventProducer, never()).publishBidAccepted(any());
        verify(sosEventProducer, never()).publishBidRejected(any());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void updateStatus_accepted_autoRejectsOtherBidsAndUpdatesSosStatus() {
        BidSos otherBid = BidSos.builder().bidId(UUID.randomUUID()).price(BigDecimal.valueOf(400))
                .sosRequest(pendingSos).mechanic(mechanic).build();
        UpdateBidStatusRequest request = new UpdateBidStatusRequest(userId, BidStatus.ACCEPTED);
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidSosRepository.save(bid)).thenReturn(bid);
        when(bidSosRepository.findBySosRequestSosIdAndStatus(sosId, BidStatus.PENDING))
                .thenReturn(List.of(otherBid));
        when(bidSosRepository.saveAll(anyList())).thenReturn(List.of(otherBid));
        when(sosRequestRepository.save(pendingSos)).thenReturn(pendingSos);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.updateStatus(bidId, request);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(otherBid.getStatus()).isEqualTo(BidStatus.REJECTED);
        assertThat(pendingSos.getStatus()).isEqualTo(SosStatus.ACCEPTED);
        verify(bidSosRepository).saveAll(List.of(otherBid));
        verify(sosRequestRepository).save(pendingSos);

        ArgumentCaptor<BidAcceptedEvent> acceptedCaptor = ArgumentCaptor.forClass(BidAcceptedEvent.class);
        verify(sosEventProducer).publishBidAccepted(acceptedCaptor.capture());
        BidAcceptedEvent acceptedEvent = acceptedCaptor.getValue();
        assertThat(acceptedEvent.bidId()).isEqualTo(bidId);
        assertThat(acceptedEvent.sosId()).isEqualTo(sosId);
        assertThat(acceptedEvent.mechanicId()).isEqualTo(mechanicId);
        assertThat(acceptedEvent.userId()).isEqualTo(userId);
        assertThat(acceptedEvent.latitude()).isEqualTo(13.7563);
        assertThat(acceptedEvent.longitude()).isEqualTo(100.5018);
        verify(messagingTemplate).convertAndSendToUser(mechanicId.toString(), "/queue/sos-updates", acceptedEvent);

        ArgumentCaptor<BidRejectedEvent> rejectedCaptor = ArgumentCaptor.forClass(BidRejectedEvent.class);
        verify(sosEventProducer).publishBidRejected(rejectedCaptor.capture());
        assertThat(rejectedCaptor.getValue().sosId()).isEqualTo(sosId);
        assertThat(rejectedCaptor.getValue().bidIds()).containsExactly(otherBid.getBidId());

        verify(sosEventProducer, times(2)).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void updateStatus_bidNotFound_throwsEntityNotFoundException() {
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bidService.updateStatus(bidId, new UpdateBidStatusRequest(userId, BidStatus.ACCEPTED)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(bidId.toString());
    }

    @Test
    void updateStatus_callerNotSosOwner_throwsIllegalStateException() {
        UUID otherUserId = UUID.randomUUID();
        UpdateBidStatusRequest request = new UpdateBidStatusRequest(otherUserId, BidStatus.ACCEPTED);
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> bidService.updateStatus(bidId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");

        verify(bidSosRepository, never()).save(any());
        verify(sosEventProducer, never()).publishBidAccepted(any());
    }

    @Test
    void updateStatus_cancelledStatus_throwsIllegalStateException() {
        UpdateBidStatusRequest request = new UpdateBidStatusRequest(userId, BidStatus.CANCELLED);

        assertThatThrownBy(() -> bidService.updateStatus(bidId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancel endpoint");

        verify(bidSosRepository, never()).findById(any());
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_pendingBidOwnedByMechanic_setsCancelledStatus() {
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidSosRepository.save(bid)).thenReturn(bid);
        when(bidSosMapper.toResponse(bid)).thenReturn(bidResponse);

        bidService.cancel(bidId, mechanicId);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getRespondedAt()).isNotNull();

        ArgumentCaptor<BidCancelledEvent> captor = ArgumentCaptor.forClass(BidCancelledEvent.class);
        verify(sosEventProducer).publishBidCancelled(captor.capture());
        assertThat(captor.getValue().bidId()).isEqualTo(bidId);
        assertThat(captor.getValue().sosId()).isEqualTo(sosId);
        assertThat(captor.getValue().mechanicId()).isEqualTo(mechanicId);
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
    }

    @Test
    void cancel_bidNotFound_throwsEntityNotFoundException() {
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bidService.cancel(bidId, mechanicId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(bidId.toString());
    }

    @Test
    void cancel_callerNotBidOwner_throwsIllegalStateException() {
        UUID otherMechanicId = UUID.randomUUID();
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> bidService.cancel(bidId, otherMechanicId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not authorized");

        verify(bidSosRepository, never()).save(any());
        verify(sosEventProducer, never()).publishBidCancelled(any());
    }

    @Test
    void cancel_bidNotPending_throwsIllegalStateException() {
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidSosRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThatThrownBy(() -> bidService.cancel(bidId, mechanicId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");

        verify(bidSosRepository, never()).save(any());
    }
}
