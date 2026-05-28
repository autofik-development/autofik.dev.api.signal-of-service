package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.SosStatusHistory;
import com.autofik.sos.dto.response.SosStatusHistoryResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring", uses = {MechanicMapper.class, UserMapper.class})
public interface SosStatusHistoryMapper {

    // proposedBy (Mechanic) → MechanicResponse, approvedBy (User) → UserResponse — resolved via uses
    SosStatusHistoryResponse toResponse(SosStatusHistory history);

    List<SosStatusHistoryResponse> toResponseList(List<SosStatusHistory> histories);
}
