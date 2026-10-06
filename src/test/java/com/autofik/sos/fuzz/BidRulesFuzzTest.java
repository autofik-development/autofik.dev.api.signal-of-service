package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateBidRequest;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.BidSosMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.impl.BidServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Placing a bid, over every combination of ticket status, bidding-window position, mechanic
 * availability and prior bid. A bid is stored only when all four allow it; anything else is a
 * conflict that stores and announces nothing.
 */
class BidRulesFuzzTest {

  @Mock BidSosRepository bidSosRepository;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock BidSosMapper bidSosMapper;
  @Mock GoogleMapsService googleMapsService;
  @Mock SosEventProducer sosEventProducer;
  @Mock SimpMessagingTemplate messagingTemplate;

  @Mock org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  @Mock com.autofik.sos.service.SosService sosService;

  @InjectMocks BidServiceImpl bidService;

  private AutoCloseable mocks;
  private final UUID sosId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();

  @BeforeTry
  void freshService() {
    bidService = null;
    mocks = MockitoAnnotations.openMocks(this);
    // create() runs its database phases through the template; run the callback inline.
    when(transactionTemplate.execute(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv ->
                ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0))
                    .doInTransaction(null));
  }

  @AfterTry
  void closeMocks() throws Exception {
    mocks.close();
  }

  /**
   * @param windowOffsetSeconds where "now" sits relative to the window's end: positive means the
   *     window is still open by that many seconds, negative means it closed that long ago, null
   *     means no window was set. Never within 2 s of the edge, so the clock cannot flip the case.
   */
  @Property(seed = Fuzz.SEED)
  void bidIsStoredOnlyWhenEveryRuleAllowsIt(
      @ForAll SosStatus status,
      @ForAll("windowOffsets") Long windowOffsetSeconds,
      @ForAll("availability") Boolean available,
      @ForAll boolean alreadyBid,
      @ForAll boolean eligible) {
    SosRequest sos =
        SosRequest.builder()
            .sosId(sosId)
            .status(status)
            .user(User.builder().userId(UUID.randomUUID()).name("Alice").build())
            .biddingExpiresAt(
                windowOffsetSeconds == null
                    ? null
                    : LocalDateTime.now().plusSeconds(windowOffsetSeconds))
            .build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(sosRequestRepository.lockStatus(sosId)).thenReturn(Optional.of(status.name()));
    when(mechanicRepository.findById(mechanicId))
        .thenReturn(
            Optional.of(
                Mechanic.builder()
                    .mechanicId(mechanicId)
                    .fullName("Bob")
                    .phoneNumber("081")
                    .availableStatus(available)
                    .build()));
    when(bidSosRepository.existsBySosRequestSosIdAndMechanicMechanicId(sosId, mechanicId))
        .thenReturn(alreadyBid);
    when(sosService.isEligibleMechanic(any(), any())).thenReturn(eligible);
    when(bidSosRepository.save(any(BidSos.class))).thenAnswer(inv -> inv.getArgument(0));
    when(bidSosMapper.toResponse(any()))
        .thenReturn(new BidSosResponse(null, null, null, null, null, null, null, null, null, null));

    Throwable thrown = null;
    try {
      bidService.create(new CreateBidRequest(mechanicId, sosId, new BigDecimal("25.00")));
    } catch (Throwable t) {
      thrown = t;
    }

    boolean windowOpen = windowOffsetSeconds == null || windowOffsetSeconds > 0;
    boolean openAndAvailable =
        status == SosStatus.PENDING && windowOpen && Boolean.TRUE.equals(available);
    // Order of the rules: ticket open, mechanic available, mechanic eligible (404), no duplicate.
    boolean allowed = openAndAvailable && eligible && !alreadyBid;
    String c =
        "status="
            + status
            + " window="
            + windowOffsetSeconds
            + "s available="
            + available
            + " alreadyBid="
            + alreadyBid
            + " eligible="
            + eligible;
    if (allowed) {
      assertThat(thrown).as(c).isNull();
      verify(bidSosRepository).save(any(BidSos.class));
      verify(sosEventProducer).publishBidCreated(any());
    } else {
      assertThat(thrown)
          .as(c)
          .isInstanceOf(
              openAndAvailable && !eligible
                  ? jakarta.persistence.EntityNotFoundException.class
                  : IllegalStateException.class);
      verify(bidSosRepository, never()).save(any());
      verify(sosEventProducer, never()).publishBidCreated(any());
      verify(sosEventProducer, never()).publishNotification(any());
    }
  }

  @Provide
  Arbitrary<Long> windowOffsets() {
    return Arbitraries.oneOf(
        Arbitraries.longs().between(2, 7_200),
        Arbitraries.longs().between(-7_200, -2),
        Arbitraries.just(null));
  }

  @Provide
  Arbitrary<Boolean> availability() {
    return Arbitraries.of(true, false).injectNull(0.2);
  }
}
