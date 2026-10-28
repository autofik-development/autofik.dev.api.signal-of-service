package com.autofik.sos.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The edges of the generated mappers: a null element, a null list, an empty list, and a skill with
 * no tags or no parent. The normal mappings are in {@link MapperTest}.
 */
class MapperEdgeCasesTest {

  private final UserMapperImpl users = new UserMapperImpl();
  private final VehicleMapperImpl vehicles = new VehicleMapperImpl();
  private final MechanicMapperImpl mechanics = new MechanicMapperImpl();
  private final MechanicSkillMapperImpl skills = new MechanicSkillMapperImpl();

  private SosMapperImpl sosMapper() {
    SosMapperImpl mapper = new SosMapperImpl();
    ReflectionTestUtils.setField(mapper, "userMapper", users);
    ReflectionTestUtils.setField(mapper, "vehicleMapper", vehicles);
    return mapper;
  }

  @Test
  void aNullList_mapsToNull_andAnEmptyOne_toEmpty() {
    assertThat(users.toResponseList(null)).isNull();
    assertThat(vehicles.toResponseList(null)).isNull();
    assertThat(mechanics.toResponseList(null)).isNull();
    assertThat(mechanics.toSummaryList(null)).isNull();
    assertThat(skills.toResponseList(null)).isNull();
    assertThat(sosMapper().toResponseList(null)).isNull();

    assertThat(users.toResponseList(List.of())).isEmpty();
    assertThat(vehicles.toResponseList(List.of())).isEmpty();
    assertThat(mechanics.toResponseList(List.of())).isEmpty();
    assertThat(mechanics.toSummaryList(List.of())).isEmpty();
    assertThat(skills.toResponseList(List.of())).isEmpty();
    assertThat(sosMapper().toResponseList(List.of())).isEmpty();
  }

  @Test
  void aNullElement_mapsToNull() {
    assertThat(users.toResponse(null)).isNull();
    assertThat(vehicles.toResponse(null)).isNull();
    assertThat(mechanics.toResponse(null)).isNull();
    assertThat(mechanics.toSummary(null)).isNull();
    assertThat(mechanics.toSummaryWithoutPosition(null)).isNull();
    assertThat(skills.toResponse(null)).isNull();
    assertThat(sosMapper().toResponse(null)).isNull();
  }

  @Test
  void lists_mapEveryElement_inOrder() {
    User a = User.builder().userId(UUID.randomUUID()).name("A").phone("1").build();
    User b = User.builder().userId(UUID.randomUUID()).name("B").phone("2").build();
    Vehicle v = Vehicle.builder().id("v1").carBrand("Toyota").build();

    assertThat(users.toResponseList(List.of(a, b)))
        .extracting(r -> r.name())
        .containsExactly("A", "B");
    assertThat(vehicles.toResponseList(List.of(v)))
        .extracting(r -> r.carBrand())
        .containsExactly("Toyota");
  }

  @Test
  void aMechanicSummary_withoutAPosition_hasNullCoordinates_andNeverFailsOnAMissingPoint() {
    Mechanic m = Mechanic.builder().mechanicId(UUID.randomUUID()).fullName("Bob").build();

    assertThat(mechanics.toSummary(m).latitude()).isNull();
    assertThat(mechanics.toSummary(m).longitude()).isNull();
    assertThat(mechanics.toResponse(m).latitude()).isNull();
    assertThat(mechanics.toSummaryList(List.of(m))).hasSize(1);
    assertThat(mechanics.toResponseList(List.of(m))).hasSize(1);
  }

  @Test
  void aSkill_withNoTagsAndNoParent_mapsWithoutFailing() {
    MechanicSkill noParent =
        MechanicSkill.builder().id(UUID.randomUUID()).vehicleSpecialization("ALL").build();

    var response = skills.toResponse(noParent);

    assertThat(response.mechanicId()).isNull();
    assertThat(response.skillTags()).isNull();
    assertThat(response.vehicleSpecialization()).isEqualTo("ALL");
  }

  @Test
  void aSosWithoutAnyNestedObject_mapsWithNullParts() {
    SosRequest sos = SosRequest.builder().sosId(UUID.randomUUID()).build();

    var response = sosMapper().toResponse(sos);

    assertThat(response.sosId()).isEqualTo(sos.getSosId());
    assertThat(response.user()).isNull();
    assertThat(response.vehicle()).isNull();
    assertThat(response.latitude()).isNull();
    assertThat(response.durationSeconds()).isNull();
  }

  @Test
  void nonEmptyLists_mapEveryElement_ofTheRemainingMappers() {
    MechanicSkill skill =
        MechanicSkill.builder().id(UUID.randomUUID()).vehicleSpecialization("ALL").build();
    SosRequest sos = SosRequest.builder().sosId(UUID.randomUUID()).build();
    var history =
        com.autofik.sos.domain.entity.SosStatusHistory.builder().id(UUID.randomUUID()).build();
    var bid = com.autofik.sos.domain.entity.BidSos.builder().bidId(UUID.randomUUID()).build();

    assertThat(skills.toResponseList(List.of(skill))).hasSize(1);
    assertThat(sosMapper().toResponseList(List.of(sos))).hasSize(1);
    var historyMapper = new SosStatusHistoryMapperImpl();
    ReflectionTestUtils.setField(historyMapper, "mechanicMapper", mechanics);
    ReflectionTestUtils.setField(historyMapper, "userMapper", users);
    assertThat(historyMapper.toResponseList(null)).isNull();
    assertThat(historyMapper.toResponseList(List.of(history))).hasSize(1);
    var bidMapper = new BidSosMapperImpl();
    ReflectionTestUtils.setField(bidMapper, "mechanicMapper", mechanics);
    assertThat(bidMapper.toResponseList(List.of(bid))).hasSize(1);
  }

  @Test
  void summaryWithoutPosition_carriesTheRatingWhenThereIsOne() {
    var rate =
        com.autofik.sos.domain.entity.MechanicRate.builder()
            .ratingAvg(new java.math.BigDecimal("4.5"))
            .build();
    Mechanic rated =
        Mechanic.builder().mechanicId(UUID.randomUUID()).fullName("Bob").rate(rate).build();
    Mechanic unrated = Mechanic.builder().mechanicId(UUID.randomUUID()).fullName("Al").build();

    assertThat(mechanics.toSummaryWithoutPosition(rated).avgRate()).isEqualByComparingTo("4.5");
    assertThat(mechanics.toSummaryWithoutPosition(unrated).avgRate()).isNull();
  }

  @Test
  void bidMapper_nullList_isNull() {
    assertThat(new BidSosMapperImpl().toResponseList(null)).isNull();
  }
}
