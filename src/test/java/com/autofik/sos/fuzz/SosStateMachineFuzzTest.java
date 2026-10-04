package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.ProposeStatusChangeRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.MechanicMapper;
import com.autofik.sos.mapper.SosMapper;
import com.autofik.sos.mapper.SosStatusHistoryMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.SosStatusHistoryRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.repository.VehicleRepository;
import com.autofik.sos.service.GoogleMapsService;
import com.autofik.sos.service.MedusaService;
import com.autofik.sos.service.RouteEtaService;
import com.autofik.sos.service.SosMediaService;
import com.autofik.sos.service.impl.SosServiceImpl;
import com.autofik.sos.service.impl.SsoProjection;
import com.uber.h3core.H3Core;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SOS state machine and the H3 pre-filter, against SosServiceImpl with mocked collaborators.
 *
 * <p>The allowed transitions are written out below from the documented lifecycle, not read from the
 * service, so a change to the service's table that drifts from the documented one fails here.
 */
class SosStateMachineFuzzTest {

  /** README lifecycle. COMPLETED, CANCELLED and EXPIRED are terminal. */
  private static final Map<SosStatus, Set<SosStatus>> SPEC =
      Map.of(
          SosStatus.PENDING, EnumSet.of(SosStatus.ACCEPTED, SosStatus.CANCELLED),
          SosStatus.ACCEPTED, EnumSet.of(SosStatus.ON_THE_WAY, SosStatus.CANCELLED),
          SosStatus.ON_THE_WAY, EnumSet.of(SosStatus.ARRIVED, SosStatus.CANCELLED),
          SosStatus.ARRIVED, EnumSet.of(SosStatus.IN_PROGRESS, SosStatus.CANCELLED),
          SosStatus.IN_PROGRESS, EnumSet.of(SosStatus.COMPLETED, SosStatus.CANCELLED));

  /** The statuses a mechanic may set on PATCH /status; COMPLETED goes through approval. */
  private static final Set<SosStatus> DIRECT =
      EnumSet.of(SosStatus.ON_THE_WAY, SosStatus.ARRIVED, SosStatus.IN_PROGRESS);

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);
  private static final H3Core H3 = h3();
  private static final double EARTH_RADIUS_M = 6_371_000d;

  @Mock SosRequestRepository sosRequestRepository;
  @Mock SsoProjection ssoProjection;
  @Mock UserRepository userRepository;
  @Mock VehicleRepository vehicleRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock SosStatusHistoryRepository sosStatusHistoryRepository;
  @Mock SosMapper sosMapper;
  @Mock SosStatusHistoryMapper sosStatusHistoryMapper;
  @Mock MechanicMapper mechanicMapper;
  @Mock GoogleMapsService googleMapsService;
  @Mock RouteEtaService routeEtaService;
  @Mock MedusaService medusaService;
  @Mock SosEventProducer sosEventProducer;
  @Mock SimpMessagingTemplate messagingTemplate;
  @Mock TaskScheduler taskScheduler;
  @Mock StringRedisTemplate redisTemplate;
  @Mock TransactionTemplate transactionTemplate;
  @Mock ObjectProvider<SosMediaService> sosMediaService;

  @InjectMocks SosServiceImpl sosService;

  private AutoCloseable mocks;
  private final UUID sosId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();
  private Mechanic mechanic;

  @BeforeTry
  void freshService() {
    sosService = null; // so @InjectMocks builds a new one around this try's mocks
    mocks = MockitoAnnotations.openMocks(this);
    ReflectionTestUtils.setField(sosService, "arrivalMaxDistanceMeters", 50);
    ReflectionTestUtils.setField(sosService, "h3Resolution", 7);
    ReflectionTestUtils.setField(sosService, "h3CenterToCenterKm", 2.114);
    ReflectionTestUtils.setField(sosService, "earthRadiusM", EARTH_RADIUS_M);
    // Parked at the breakdown, so ARRIVED passes the distance gate whenever it is reachable.
    mechanic =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Bob")
            .phoneNumber("081")
            .currentLocation(GEO.createPoint(new Coordinate(104.9282, 11.5564)))
            .build();
  }

  @AfterTry
  void closeMocks() throws Exception {
    mocks.close();
  }

  /**
   * PATCH /status from any state to any state: it succeeds exactly when the target is one a
   * mechanic may set directly and the documented lifecycle allows the step. A refused step leaves
   * the ticket where it was and records and announces nothing.
   */
  @Property(seed = Fuzz.SEED)
  void updateStatus_succeedsExactlyForDocumentedMechanicSteps(
      @ForAll SosStatus from, @ForAll SosStatus to) {
    SosRequest sos = ticketAt(from);
    boolean allowed = DIRECT.contains(to) && SPEC.getOrDefault(from, Set.of()).contains(to);

    Throwable thrown = catchAny(() -> sosService.updateStatus(sosId, mechanicId, to));

    if (allowed) {
      assertThat(thrown).as("%s -> %s", from, to).isNull();
      assertThat(sos.getStatus()).isEqualTo(to);
      verify(sosStatusHistoryRepository).save(any(SosStatusHistory.class));
      verify(sosEventProducer).publishStatusUpdated(any());
    } else {
      assertThat(thrown)
          .as("%s -> %s", from, to)
          .isInstanceOf(
              DIRECT.contains(to) ? IllegalStateException.class : IllegalArgumentException.class);
      assertThat(sos.getStatus()).isEqualTo(from);
      verify(sosStatusHistoryRepository, never()).save(any());
      verify(sosEventProducer, never()).publishStatusUpdated(any());
    }
  }

  /** POST /completion: only COMPLETED, and only from IN_PROGRESS; never moves the ticket itself. */
  @Property(seed = Fuzz.SEED)
  void proposeCompletion_onlyFromInProgress_andNeverMovesTheTicket(
      @ForAll SosStatus from, @ForAll SosStatus to) {
    SosRequest sos = ticketAt(from);
    when(sosStatusHistoryRepository.save(any(SosStatusHistory.class)))
        .thenAnswer(
            inv -> {
              Object history = inv.getArgument(0);
              ReflectionTestUtils.setField(history, "id", UUID.randomUUID());
              return history;
            });

    Throwable thrown =
        catchAny(
            () ->
                sosService.proposeStatusChange(
                    sosId, new ProposeStatusChangeRequest(mechanicId, to)));

    boolean allowed = to == SosStatus.COMPLETED && from == SosStatus.IN_PROGRESS;
    assertThat(thrown == null).as("%s -> %s, thrown %s", from, to, thrown).isEqualTo(allowed);
    assertThat(sos.getStatus()).as("a proposal must not change the status").isEqualTo(from);
    verify(sosEventProducer, never()).publishStatusUpdated(any());
  }

  /**
   * Stage 1 of the mechanic search keeps only mechanics whose H3 cell is in kRing(sosCell, k).
   * Stage 2 then keeps those within the radius. So every point within the radius must already be
   * inside the ring, or a mechanic who qualifies is silently dropped before the exact check.
   */
  @Property(seed = Fuzz.SEED)
  void h3Ring_coversEveryPointWithinTheSearchRadius(
      @ForAll("cambodiaLat") double lat,
      @ForAll("cambodiaLng") double lng,
      @ForAll @IntRange(min = 1, max = 35) int radiusKm,
      @ForAll @DoubleRange(min = 0, max = 360) double bearingDeg,
      @ForAll @DoubleRange(min = 0, max = 1) double fraction) {
    double[] p = destination(lat, lng, bearingDeg, radiusKm * 1000.0 * fraction);
    int k = ReflectionTestUtils.invokeMethod(sosService, "ringRadiusFor", radiusKm);

    List<String> ring = H3.kRing(H3.geoToH3Address(lat, lng, 7), k);

    assertThat(ring)
        .as(
            "point %.6f,%.6f is %.0f m from %.6f,%.6f (radius %d km, k=%d)",
            p[0], p[1], radiusKm * 1000.0 * fraction, lat, lng, radiusKm, k)
        .contains(H3.geoToH3Address(p[0], p[1], 7));
  }

  // ── generators and fixtures ────────────────────────────────────────────────

  @Provide
  Arbitrary<Double> cambodiaLat() {
    return Arbitraries.doubles().between(10.41, 14.69);
  }

  @Provide
  Arbitrary<Double> cambodiaLng() {
    return Arbitraries.doubles().between(102.5, 107.63);
  }

  /** A ticket in the given state whose accepted bid belongs to {@link #mechanic}. */
  private SosRequest ticketAt(SosStatus status) {
    SosRequest sos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType("TOW")
            .status(status)
            .location(GEO.createPoint(new Coordinate(104.9282, 11.5564)))
            .user(User.builder().userId(UUID.randomUUID()).name("Alice").build())
            .build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(sos));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(
            Optional.of(
                BidSos.builder()
                    .bidId(UUID.randomUUID())
                    .status(BidStatus.ACCEPTED)
                    .sosRequest(sos)
                    .mechanic(mechanic)
                    .build()));
    when(sosMapper.toResponse(sos))
        .thenReturn(
            new SosRequestResponse(
                sosId, null, null, null, status, null, null, null, null, null, null, null, null,
                null, 0, null, null, null));
    return sos;
  }

  private static Throwable catchAny(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** The point reached by travelling distanceM from (lat, lng) on the given initial bearing. */
  private static double[] destination(double lat, double lng, double bearingDeg, double distanceM) {
    double d = distanceM / EARTH_RADIUS_M;
    double b = Math.toRadians(bearingDeg);
    double phi1 = Math.toRadians(lat);
    double lambda1 = Math.toRadians(lng);
    double phi2 =
        Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(b));
    double lambda2 =
        lambda1
            + Math.atan2(
                Math.sin(b) * Math.sin(d) * Math.cos(phi1),
                Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
    return new double[] {Math.toDegrees(phi2), Math.toDegrees(lambda2)};
  }

  private static H3Core h3() {
    try {
      return H3Core.newInstance();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
