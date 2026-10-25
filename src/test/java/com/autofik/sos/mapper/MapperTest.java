package com.autofik.sos.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.ApprovedStatus;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.MediaType;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.response.BidSosResponse;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.dto.response.SosRequestResponse;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The generated MapStruct implementations, wired the way the app wires them (nested mappers come
 * from {@code uses}). The custom expressions are where bugs live: a JTS point is (x = longitude, y
 * = latitude), and swapping them puts every pin in the wrong place on the map.
 */
@SpringJUnitConfig(
    classes = {
      SosMapperImpl.class,
      UserMapperImpl.class,
      VehicleMapperImpl.class,
      MechanicMapperImpl.class,
      BidSosMapperImpl.class,
      FeedbackMapperImpl.class,
      MechanicSkillMapperImpl.class,
      SosMediaMapperImpl.class,
      SosStatusHistoryMapperImpl.class
    })
class MapperTest {

  @Autowired SosMapper sosMapper;
  @Autowired MechanicMapper mechanicMapper;
  @Autowired BidSosMapper bidMapper;
  @Autowired FeedbackMapper feedbackMapper;
  @Autowired MechanicSkillMapper skillMapper;
  @Autowired SosMediaMapper mediaMapper;
  @Autowired SosStatusHistoryMapper historyMapper;

  private static final GeometryFactory GEO = new GeometryFactory(new PrecisionModel(), 4326);
  // Phnom Penh: latitude 11.55, longitude 104.92. Distinct values so a swap is visible.
  private static final double LAT = 11.556374;
  private static final double LNG = 104.928207;

  private static Point point(double lat, double lng) {
    return GEO.createPoint(new Coordinate(lng, lat));
  }

  private static Mechanic mechanic() {
    Mechanic m =
        Mechanic.builder()
            .mechanicId(UUID.randomUUID())
            .fullName("Mech")
            .currentLocation(point(LAT, LNG))
            .build();
    m.setRate(MechanicRate.builder().ratingAvg(new BigDecimal("4.50")).ratingCount(12).build());
    return m;
  }

  private static User user() {
    return User.builder().userId(UUID.randomUUID()).name("Driver").phone("+85500000000").build();
  }

  @Test
  void mechanic_pointIsSplitIntoLatitudeAndLongitude_notSwapped() {
    MechanicResponse r = mechanicMapper.toResponse(mechanic());

    assertThat(r.latitude()).isEqualTo(LAT);
    assertThat(r.longitude()).isEqualTo(LNG);
    assertThat(r.avgRate()).isEqualByComparingTo("4.50");
    assertThat(r.rateCount()).isEqualTo(12);
  }

  // 11.556374 / 104.928207 snap to the 0.01° grid; a swapped or unsnapped point fails here.
  @Test
  void mechanicSummary_snapsThePositionAndCarriesTheRatingThrough() {
    MechanicSummaryResponse r = mechanicMapper.toSummary(mechanic());

    assertThat(r.latitude()).isEqualTo(11.56);
    assertThat(r.longitude()).isEqualTo(104.93);
    assertThat(r.avgRate()).isEqualByComparingTo("4.50");
  }

  @Test
  void mechanicSummaryWithoutPosition_hasNoPosition() {
    MechanicSummaryResponse r = mechanicMapper.toSummaryWithoutPosition(mechanic());

    assertThat(r.latitude()).isNull();
    assertThat(r.longitude()).isNull();
    assertThat(r.fullName()).isEqualTo(mechanic().getFullName());
  }

  // The list must use the snapped mapping, never the position-less one or the full response.
  @Test
  void mechanicSummaryList_usesTheSnappedMapping() {
    assertThat(mechanicMapper.toSummaryList(List.of(mechanic())))
        .singleElement()
        .extracting(MechanicSummaryResponse::latitude)
        .isEqualTo(11.56);
  }

  @Test
  void mechanic_withoutLocationOrRating_mapsToNulls() {
    MechanicResponse r =
        mechanicMapper.toResponse(Mechanic.builder().mechanicId(UUID.randomUUID()).build());

    assertThat(r.latitude()).isNull();
    assertThat(r.longitude()).isNull();
    assertThat(r.avgRate()).isNull();
    assertThat(r.rateCount()).isNull();
    assertThat(r.availableStatus()).isTrue(); // entity default
  }

  @Test
  void sos_mapsLocationNestedUserAndVehicle_andLeavesMechanicForTheService() {
    User u = user();
    Vehicle v =
        Vehicle.builder().id(UUID.randomUUID().toString()).carBrand("Toyota").user(u).build();
    LocalDateTime start = LocalDateTime.of(2026, 10, 3, 10, 0);
    SosRequest sos =
        SosRequest.builder()
            .sosId(UUID.randomUUID())
            .problemType("TOW")
            .status(SosStatus.COMPLETED)
            .location(point(LAT, LNG))
            .broadcastRadiusKm(15)
            .jobStartedAt(start)
            .jobCompletedAt(start.plusSeconds(90))
            .user(u)
            .vehicle(v)
            .build();

    SosRequestResponse r = sosMapper.toResponse(sos);

    assertThat(r.latitude()).isEqualTo(LAT);
    assertThat(r.longitude()).isEqualTo(LNG);
    assertThat(r.durationSeconds()).isEqualTo(90L);
    assertThat(r.user().userId()).isEqualTo(u.getUserId());
    assertThat(r.vehicle().carBrand()).isEqualTo("Toyota");
    assertThat(r.mechanic()).isNull();
    assertThat(r.broadcastRadiusKm()).isEqualTo(15);
  }

  @Test
  void sos_withoutLocation_doesNotThrow() {
    SosRequestResponse r = sosMapper.toResponse(SosRequest.builder().build());
    assertThat(r.latitude()).isNull();
    assertThat(r.status()).isEqualTo(SosStatus.PENDING);
  }

  @Test
  void bid_carriesItsMechanicThroughTheNestedMapper() {
    BidSos bid =
        BidSos.builder()
            .bidId(UUID.randomUUID())
            .price(new BigDecimal("25.00"))
            .estimatedDistanceM(1200L)
            .mechanic(mechanic())
            .build();

    List<BidSosResponse> rs = bidMapper.toResponseList(List.of(bid));

    assertThat(rs).hasSize(1);
    assertThat(rs.get(0).status()).isEqualTo(BidStatus.PENDING);
    assertThat(rs.get(0).mechanic().latitude()).isEqualTo(LAT);
    assertThat(rs.get(0).estimatedDistanceM()).isEqualTo(1200L);
  }

  @Test
  void feedback_and_history_mapNestedParticipants() {
    Mechanic m = mechanic();
    User u = user();
    Feedback f =
        Feedback.builder().id(UUID.randomUUID()).rating(BigDecimal.ONE).user(u).mechanic(m).build();
    SosStatusHistory h =
        SosStatusHistory.builder()
            .id(UUID.randomUUID())
            .fromStatus(SosStatus.ACCEPTED)
            .toStatus(SosStatus.ON_THE_WAY)
            .approvedStatus(ApprovedStatus.ACCEPTED)
            .proposedBy(m)
            .approvedBy(u)
            .build();

    assertThat(feedbackMapper.toResponse(f).mechanic().mechanicId()).isEqualTo(m.getMechanicId());
    SosStatusHistoryResponse hr = historyMapper.toResponseList(List.of(h)).get(0);
    assertThat(hr.proposedBy().mechanicId()).isEqualTo(m.getMechanicId());
    assertThat(hr.approvedBy().userId()).isEqualTo(u.getUserId());
    assertThat(hr.toStatus()).isEqualTo(SosStatus.ON_THE_WAY);
  }

  @Test
  void skill_takesMechanicIdFromTheParent_andCopiesTagsArray() {
    Mechanic m = mechanic();
    MechanicSkill s =
        MechanicSkill.builder()
            .id(UUID.randomUUID())
            .mechanic(m)
            .skillTags(new String[] {"TOW", "TIRE"})
            .build();

    var r = skillMapper.toResponse(s);

    assertThat(r.mechanicId()).isEqualTo(m.getMechanicId());
    assertThat(r.skillTags()).containsExactly("TOW", "TIRE");
  }

  @Test
  void media_neverLeaksTheStoredObjectKeyAsAUrl() {
    SosMedia media =
        SosMedia.builder()
            .mediaId(UUID.randomUUID())
            .mediaType(MediaType.PHOTO)
            .objectKey("sos/secret/key.jpg")
            .sizeBytes(10L)
            .build();

    // url is filled with a fresh presigned link by the service, never copied from the key.
    assertThat(mediaMapper.toResponse(media).url()).isNull();
  }

  @Test
  void nullInputs_mapToNull() {
    assertThat(sosMapper.toResponse(null)).isNull();
    assertThat(sosMapper.toResponseList(null)).isNull();
    assertThat(mechanicMapper.toResponse(null)).isNull();
    assertThat(bidMapper.toResponse(null)).isNull();
    assertThat(feedbackMapper.toResponseList(null)).isNull();
    assertThat(mediaMapper.toResponse(null)).isNull();
    assertThat(historyMapper.toResponse(null)).isNull();
  }
}
