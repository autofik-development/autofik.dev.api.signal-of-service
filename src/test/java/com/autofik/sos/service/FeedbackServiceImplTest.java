package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.BidStatus;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.mapper.FeedbackMapper;
import com.autofik.sos.repository.BidSosRepository;
import com.autofik.sos.repository.FeedbackRepository;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.FeedbackServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

  @Mock FeedbackRepository feedbackRepository;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock UserRepository userRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock MechanicRateRepository mechanicRateRepository;
  @Mock BidSosRepository bidSosRepository;
  @Mock FeedbackMapper feedbackMapper;

  @InjectMocks FeedbackServiceImpl feedbackService;

  private final UUID userId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();
  private final UUID sosId = UUID.randomUUID();
  private final UUID feedbackId = UUID.randomUUID();

  private User user;
  private Mechanic mechanic;
  private SosRequest completedSos;
  private Feedback feedback;
  private FeedbackResponse feedbackResponse;

  @BeforeEach
  void setUp() {
    user = User.builder().userId(userId).name("Alice").build();
    mechanic =
        Mechanic.builder()
            .mechanicId(mechanicId)
            .fullName("Bob")
            .phoneNumber("081")
            .email("bob@fix.com")
            .build();
    Vehicle vehicle =
        Vehicle.builder()
            .id(UUID.randomUUID().toString())
            .carBrand("Toyota")
            .carModel("Camry")
            .build();
    completedSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType("Flat Tire")
            .status(SosStatus.COMPLETED)
            .user(user)
            .vehicle(vehicle)
            .build();
    feedback =
        Feedback.builder()
            .id(feedbackId)
            .rating(BigDecimal.valueOf(4.5))
            .user(user)
            .mechanic(mechanic)
            .sosRequest(completedSos)
            .build();
    feedbackResponse =
        new FeedbackResponse(feedbackId, null, BigDecimal.valueOf(4.5), null, null, null);
    // By default the mechanic under test is the one who won this job.
    lenient()
        .when(bidSosRepository.findFirstBySosRequestSosIdAndStatus(sosId, BidStatus.ACCEPTED))
        .thenReturn(Optional.of(BidSos.builder().mechanic(mechanic).build()));
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_completedSos_savesAndRecalculatesMechanicRating() {
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(
            userId, mechanicId, sosId, BigDecimal.valueOf(4.5), "Great service");
    Feedback secondFeedback =
        Feedback.builder()
            .id(UUID.randomUUID())
            .rating(BigDecimal.valueOf(3.5))
            .user(user)
            .mechanic(mechanic)
            .sosRequest(completedSos)
            .build();

    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(feedbackRepository.save(any(Feedback.class))).thenReturn(feedback);
    // Two feedbacks → average = (4.5 + 3.5) / 2 = 4.0
    when(feedbackRepository.findByMechanicMechanicId(mechanicId))
        .thenReturn(List.of(feedback, secondFeedback));
    when(mechanicRateRepository.findByMechanic(mechanic)).thenReturn(Optional.empty());
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    FeedbackResponse result = feedbackService.create(request);

    assertThat(result).isEqualTo(feedbackResponse);
    ArgumentCaptor<MechanicRate> captor = ArgumentCaptor.forClass(MechanicRate.class);
    verify(mechanicRateRepository).save(captor.capture());
    assertThat(captor.getValue().getRatingAvg()).isEqualByComparingTo(BigDecimal.valueOf(4.0));
  }

  @Test
  void create_savesCorrectFields() {
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(userId, mechanicId, sosId, BigDecimal.valueOf(5.0), "Excellent");
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(feedbackRepository.save(any(Feedback.class))).thenReturn(feedback);
    when(feedbackRepository.findByMechanicMechanicId(mechanicId)).thenReturn(List.of(feedback));
    when(mechanicRateRepository.findByMechanic(mechanic)).thenReturn(Optional.empty());
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    feedbackService.create(request);

    ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
    verify(feedbackRepository).save(captor.capture());
    Feedback saved = captor.getValue();
    assertThat(saved.getRating()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
    assertThat(saved.getComment()).isEqualTo("Excellent");
    assertThat(saved.getUser()).isEqualTo(user);
    assertThat(saved.getMechanic()).isEqualTo(mechanic);
    assertThat(saved.getSosRequest()).isEqualTo(completedSos);
  }

  @Test
  void create_sosNotCompleted_throwsIllegalStateException() {
    SosRequest pendingSos =
        SosRequest.builder()
            .sosId(sosId)
            .problemType("X")
            .status(SosStatus.PENDING)
            .user(user)
            .vehicle(Vehicle.builder().id(UUID.randomUUID().toString()).build())
            .build();
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));

    assertThatThrownBy(() -> feedbackService.create(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMPLETED");
  }

  @Test
  void create_userNotFound_throwsEntityNotFoundException() {
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> feedbackService.create(request))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(userId.toString());
  }

  @Test
  void create_mechanicNotFound_throwsEntityNotFoundException() {
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> feedbackService.create(request))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(mechanicId.toString());
  }

  // ── getBySosRequest ───────────────────────────────────────────────────────

  @Test
  void getBySosRequest_found_returnsResponse() {
    when(feedbackRepository.findBySosRequestSosId(sosId)).thenReturn(Optional.of(feedback));
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    assertThat(feedbackService.getBySosRequest(sosId, userId)).isEqualTo(feedbackResponse);
  }

  @Test
  void getBySosRequest_notFound_throwsEntityNotFoundException() {
    when(feedbackRepository.findBySosRequestSosId(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> feedbackService.getBySosRequest(sosId, userId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(sosId.toString());
  }

  @Test
  void getBySosRequest_assignedMechanic_canRead() {
    when(feedbackRepository.findBySosRequestSosId(sosId)).thenReturn(Optional.of(feedback));
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    assertThat(feedbackService.getBySosRequest(sosId, mechanicId)).isEqualTo(feedbackResponse);
  }

  /** A stranger gets the same answer as a SOS with no feedback, so existence is not revealed. */
  @Test
  void getBySosRequest_stranger_getsNotFound() {
    when(feedbackRepository.findBySosRequestSosId(sosId)).thenReturn(Optional.of(feedback));

    assertThatThrownBy(() -> feedbackService.getBySosRequest(sosId, UUID.randomUUID()))
        .isInstanceOf(EntityNotFoundException.class);
    verify(feedbackMapper, org.mockito.Mockito.never()).toResponse(any());
  }

  @Test
  void create_reviewerIsNotTheSosOwner_isDenied() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    UUID stranger = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                feedbackService.create(
                    new CreateFeedbackRequest(
                        stranger, mechanicId, sosId, BigDecimal.valueOf(1), "bad")))
        .isInstanceOf(AccessDeniedException.class);
    verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
  }

  /** The skew attack: a real customer rating a mechanic who never worked their job. */
  @Test
  void create_mechanicWasNotAssignedToThisSos_isDenied() {
    UUID otherMechanicId = UUID.randomUUID();
    Mechanic other = Mechanic.builder().mechanicId(otherMechanicId).fullName("Eve").build();
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(mechanicRepository.findById(otherMechanicId)).thenReturn(Optional.of(other));

    assertThatThrownBy(
            () ->
                feedbackService.create(
                    new CreateFeedbackRequest(
                        userId, otherMechanicId, sosId, BigDecimal.valueOf(1), "bad")))
        .isInstanceOf(AccessDeniedException.class);
    verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
  }

  // ── getByMechanic ─────────────────────────────────────────────────────────

  @Test
  void getByMechanic_delegatesAndReturnsMappedList() {
    Pageable pageable = PageRequest.of(0, 20);
    when(feedbackRepository.findByMechanicMechanicId(mechanicId, pageable))
        .thenReturn(new PageImpl<>(List.of(feedback)));
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    Page<FeedbackResponse> result = feedbackService.getByMechanic(mechanicId, pageable);
    assertThat(result.getContent()).containsExactly(feedbackResponse);
  }

  @Test
  void create_secondFeedbackForSameSos_isRejectedBeforeAnythingIsSaved() {
    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(feedbackRepository.existsBySosRequestSosId(sosId)).thenReturn(true);

    assertThatThrownBy(
            () ->
                feedbackService.create(
                    new CreateFeedbackRequest(
                        userId, mechanicId, sosId, BigDecimal.valueOf(5), "again")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already been submitted");

    verify(feedbackRepository, org.mockito.Mockito.never()).save(any());
  }
}
