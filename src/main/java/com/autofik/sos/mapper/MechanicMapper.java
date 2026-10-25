package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.dto.response.MechanicResponse;
import com.autofik.sos.dto.response.MechanicSummaryResponse;
import com.autofik.sos.util.Geo;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring", imports = Geo.class)
public interface MechanicMapper {

  // mechanic.rate is populated by FeedbackServiceImpl.recalculateMechanicRating on every new
  // feedback; null until the mechanic has at least one.
  @Mapping(
      target = "avgRate",
      expression = "java(mechanic.getRate() != null ? mechanic.getRate().getRatingAvg() : null)")
  @Mapping(
      target = "rateCount",
      expression = "java(mechanic.getRate() != null ? mechanic.getRate().getRatingCount() : null)")
  // currentLocation is a JTS Point: X = longitude, Y = latitude (WGS-84)
  @Mapping(
      target = "latitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? mechanic.getCurrentLocation().getY() : null)")
  @Mapping(
      target = "longitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? mechanic.getCurrentLocation().getX() : null)")
  MechanicResponse toResponse(Mechanic mechanic);

  List<MechanicResponse> toResponseList(List<Mechanic> mechanics);

  // Public view: SSO's average rating read through unchanged, no count, position snapped, no
  // contact fields.
  @Mapping(
      target = "avgRate",
      expression = "java(mechanic.getRate() != null ? mechanic.getRate().getRatingAvg() : null)")
  @Mapping(
      target = "latitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? Geo.coarsen(mechanic.getCurrentLocation().getY()) : null)")
  @Mapping(
      target = "longitude",
      expression =
          "java(mechanic.getCurrentLocation() != null ? Geo.coarsen(mechanic.getCurrentLocation().getX()) : null)")
  MechanicSummaryResponse toSummary(Mechanic mechanic);

  List<MechanicSummaryResponse> toSummaryList(List<Mechanic> mechanics);

  // By-id lookups name one mechanic anywhere in the country, so they get no position at all.
  // @Named keeps MapStruct from picking this for toSummaryList.
  @Named("withoutPosition")
  @Mapping(
      target = "avgRate",
      expression = "java(mechanic.getRate() != null ? mechanic.getRate().getRatingAvg() : null)")
  @Mapping(target = "latitude", ignore = true)
  @Mapping(target = "longitude", ignore = true)
  MechanicSummaryResponse toSummaryWithoutPosition(Mechanic mechanic);
}
