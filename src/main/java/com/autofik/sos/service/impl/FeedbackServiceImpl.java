package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.mapper.FeedbackMapper;
import com.autofik.sos.repository.FeedbackRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.FeedbackService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final SosRequestRepository sosRequestRepository;
    private final UserRepository userRepository;
    private final MechanicRepository mechanicRepository;
    private final FeedbackMapper feedbackMapper;

    @Override
    @Transactional
    public FeedbackResponse create(CreateFeedbackRequest request) {
        SosRequest sosRequest = sosRequestRepository.findById(request.sosId())
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + request.sosId()));
        if (sosRequest.getStatus() != SosStatus.COMPLETED) {
            throw new IllegalStateException("Feedback can only be submitted for COMPLETED requests");
        }
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));
        Mechanic mechanic = mechanicRepository.findById(request.mechanicId())
                .orElseThrow(() -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));

        Feedback feedback = Feedback.builder()
                .rating(request.rating())
                .comment(request.comment())
                .user(user)
                .mechanic(mechanic)
                .sosRequest(sosRequest)
                .build();
        Feedback saved = feedbackRepository.save(feedback);

        recalculateMechanicRating(mechanic);

        return feedbackMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackResponse getBySosRequest(UUID sosId) {
        return feedbackMapper.toResponse(feedbackRepository.findBySosRequestSosId(sosId)
                .orElseThrow(() -> new EntityNotFoundException("Feedback not found for SOS: " + sosId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedbackResponse> getByMechanic(UUID mechanicId) {
        return feedbackMapper.toResponseList(feedbackRepository.findByMechanicMechanicId(mechanicId));
    }

    private void recalculateMechanicRating(Mechanic mechanic) {
        List<Feedback> feedbacks = feedbackRepository.findByMechanicMechanicId(mechanic.getMechanicId());
        if (feedbacks.isEmpty()) return;
        BigDecimal avg = feedbacks.stream()
                .map(Feedback::getRating)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(feedbacks.size()), 2, RoundingMode.HALF_UP);
        mechanic.setRatingAvg(avg);
        mechanicRepository.save(mechanic);
    }
}
