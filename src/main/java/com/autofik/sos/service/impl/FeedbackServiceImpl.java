package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.mapper.FeedbackMapper;
import com.autofik.sos.repository.FeedbackRepository;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.FeedbackService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
    private final MechanicRateRepository mechanicRateRepository;
    private final FeedbackMapper feedbackMapper;

    /**
     * A driver rates the job once it is finished.
     */
    @Override
    @Transactional
    public FeedbackResponse create(CreateFeedbackRequest request) {
        SosRequest sosRequest = sosRequestRepository.findById(request.sosId())
                .orElseThrow(() -> new EntityNotFoundException("SOS request not found: " + request.sosId()));

        // Rating a job that was never finished — cancelled, expired, still in progress — would let a
        // driver mark down a mechanic for work they never agreed was done.
        if (sosRequest.getStatus() != SosStatus.COMPLETED) {
            throw new ConflictException("Feedback can only be submitted for COMPLETED requests");
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
    public Page<FeedbackResponse> getByMechanic(UUID mechanicId, Pageable pageable) {
        return feedbackRepository.findByMechanicMechanicId(mechanicId, pageable)
                .map(feedbackMapper::toResponse);
    }

    /**
     * Re-aggregates the mechanic's average from the full feedback history and caches it on
     * MechanicRate, so a rating lookup never has to aggregate at read time.
     */
    private void recalculateMechanicRating(Mechanic mechanic) {
        List<Feedback> feedbacks = feedbackRepository.findByMechanicMechanicId(mechanic.getMechanicId());
        if (feedbacks.isEmpty()) return;

        BigDecimal avg = feedbacks.stream()
                .map(Feedback::getRating)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                // Two decimals, commercial rounding — what the app displays.
                .divide(BigDecimal.valueOf(feedbacks.size()), 2, RoundingMode.HALF_UP);

        MechanicRate rate = mechanicRateRepository.findByMechanic(mechanic)
                .orElse(MechanicRate.builder().mechanic(mechanic).build());
        rate.setRatingAvg(avg);
        rate.setRatingCount(feedbacks.size());
        mechanicRateRepository.save(rate);

        // TODO: publish avg_rate + rating_count back to SSO via Kafka after saving.
        // Confirm topic name with SSO team before implementing.
        // See SosEventProducer for the existing producer pattern to follow.
    }
}
