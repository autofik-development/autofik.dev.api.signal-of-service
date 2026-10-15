package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.exception.ConflictException;
import com.autofik.sos.mapper.FeedbackMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.FeedbackRepository;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.FeedbackService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service // Registers this class as a Spring-managed service bean containing business engine logic
@RequiredArgsConstructor // Lombok: Automatically generates a constructor to inject all 'private
// final' dependencies
public class FeedbackServiceImpl implements FeedbackService {

  // --- SYSTEM DEPENDENCIES (REPOSITORIES & MAPPERS) ---
  private final FeedbackRepository feedbackRepository;
  private final SosRequestRepository sosRequestRepository;
  private final UserRepository userRepository;
  private final MechanicRepository mechanicRepository;
  private final MechanicRateRepository mechanicRateRepository;
  private final BidSosRepository bidSosRepository;
  private final FeedbackMapper feedbackMapper;

  /** WORKFLOW: A driver submits a review rating and comment text for their completed rescue job. */
  @Override
  @Transactional // Ensures atomicity: if the score computation or saves fail, all table edits roll
  // back completely
  public FeedbackResponse create(CreateFeedbackRequest request) {

    // 1. VALIDATION: Verify the targeted emergency SOS ticket exists in the system
    SosRequest sosRequest =
        sosRequestRepository
            .findById(request.sosId())
            .orElseThrow(
                () -> new EntityNotFoundException("SOS request not found: " + request.sosId()));

    // 1b. AUTHORIZATION: only the driver who raised the SOS may review it. Checked before the
    // status so a stranger learns nothing about someone else's ticket.
    if (!sosRequest.getUser().getUserId().equals(request.userId())) {
      throw new AccessDeniedException("Not your SOS request");
    }

    // 2. BUSINESS RULE: Feedback cannot be left for pending, active, or canceled calls. The job
    // must be COMPLETED.
    if (sosRequest.getStatus() != SosStatus.COMPLETED) {
      throw new ConflictException("Feedback can only be submitted for COMPLETED requests");
    }

    // 2b. BUSINESS RULE: One review per job. Checked here for a clear 409; the unique sos_id column
    // remains the backstop for two submissions racing past this check.
    if (feedbackRepository.existsBySosRequestSosId(request.sosId())) {
      throw new ConflictException("Feedback has already been submitted for this SOS request");
    }

    // 3. VALIDATION: Verify the author driver profile exists
    User user =
        userRepository
            .findById(request.userId())
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + request.userId()));

    // 4. VALIDATION: Verify the target technician profile exists
    Mechanic mechanic =
        mechanicRepository
            .findById(request.mechanicId())
            .orElseThrow(
                () -> new EntityNotFoundException("Mechanic not found: " + request.mechanicId()));

    // 5. AUTHORIZATION: the review must be for the mechanic who actually did this job, otherwise
    // any driver with a completed ticket could rate (and so skew the score of) any mechanic.
    boolean assignedMechanic =
        bidSosRepository
            .findFirstBySosRequestSosIdAndStatus(request.sosId(), BidStatus.ACCEPTED)
            .map(bid -> bid.getMechanic().getMechanicId().equals(request.mechanicId()))
            .orElse(false);
    if (!assignedMechanic) {
      throw new AccessDeniedException("Not the mechanic assigned to this SOS request");
    }

    // Map the DTO input parameters into a standard Database Entity using the Builder pattern
    Feedback feedback =
        Feedback.builder()
            .rating(request.rating()) // Expecting a numeric rating (e.g., 1.00 to 5.00)
            .comment(request.comment())
            .user(user)
            .mechanic(mechanic)
            .sosRequest(sosRequest)
            .build();

    // Save the new feedback record to the database table
    Feedback saved = feedbackRepository.save(feedback);

    // --- ASYNC MATHEMATICAL UPDATE ---
    // Dynamically recalculate the overall running average score for this specific mechanic profile
    recalculateMechanicRating(mechanic);

    return feedbackMapper.toResponse(saved);
  }

  @Override
  @Transactional(
      readOnly = true) // Performance Optimization: Tells the database this is a read-only statement
  public FeedbackResponse getBySosRequest(UUID sosId, UUID callerId) {
    Feedback feedback =
        feedbackRepository
            .findBySosRequestSosId(sosId)
            .orElseThrow(() -> new EntityNotFoundException("Feedback not found for SOS: " + sosId));
    // Only the two people on the job may read it. Anyone else gets the same "not found" as a SOS
    // with no feedback, so the response does not reveal whether a given ticket was reviewed.
    boolean participant =
        feedback.getUser().getUserId().equals(callerId)
            || feedback.getMechanic().getMechanicId().equals(callerId);
    if (!participant) {
      throw new EntityNotFoundException("Feedback not found for SOS: " + sosId);
    }
    return feedbackMapper.toResponse(feedback);
  }

  /** WORKFLOW: Returns a complete list of historical reviews written about a specific mechanic. */
  @Override
  @Transactional(readOnly = true)
  public Page<FeedbackResponse> getByMechanic(UUID mechanicId, Pageable pageable) {
    return feedbackRepository
        .findByMechanicMechanicId(mechanicId, pageable)
        .map(feedbackMapper::toResponse);
  }

  /**
   * PRIVATE MATHEMATICAL HELPER: Automatically recalculates a technician's rating metrics. Every
   * time a new review lands, this function re-aggregates all scores to protect data accuracy.
   */
  private void recalculateMechanicRating(Mechanic mechanic) {
    // 1. Pull the complete historical list of reviews ever written for this specific technician
    List<Feedback> feedbacks =
        feedbackRepository.findByMechanicMechanicId(mechanic.getMechanicId());

    // 2. If the mechanic has zero reviews, stop immediately to avoid dividing by zero
    if (feedbacks.isEmpty()) return;

    // 3. STREAM STREAMLINING: Convert the list into a pipeline to extract and add up all individual
    // scores
    BigDecimal avg =
        feedbacks.stream()
            .map(Feedback::getRating) // Extract only the numeric rating value
            .reduce(
                BigDecimal.ZERO,
                BigDecimal::add) // Start a running total at 0.00 and add every score together

            // 4. DIVIDE & ROUND: Divide the total sum by the number of reviews
            .divide(
                BigDecimal.valueOf(feedbacks.size()), // Total headcount of reviews
                2, // Scale: Maintain exactly 2 decimal places (e.g., 4.67)
                RoundingMode.HALF_UP // Rounding: Enforce standard commercial rounding
                );

    MechanicRate rate =
        mechanicRateRepository
            .findByMechanic(mechanic)
            .orElse(MechanicRate.builder().mechanic(mechanic).build());
    rate.setRatingAvg(avg);
    rate.setRatingCount(feedbacks.size());
    mechanicRateRepository.save(rate);

    // TODO: publish avg_rate + rating_count back to SSO via Kafka after saving.
    // Confirm topic name with SSO team before implementing.
    // See SosEventProducer for the existing producer pattern to follow.
  }
}
