package com.autofik.sos.mapper;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.dto.response.FeedbackResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(
    componentModel = "spring",
    uses = {UserMapper.class, MechanicMapper.class})
public interface FeedbackMapper {

  FeedbackResponse toResponse(Feedback feedback);

  List<FeedbackResponse> toResponseList(List<Feedback> feedbacks);
}
