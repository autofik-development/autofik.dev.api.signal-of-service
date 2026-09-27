package com.autofik.sos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.RatingUpdateEvent;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.mapper.FeedbackMapper;
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

@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

  @Mock FeedbackRepository feedbackRepository;
  @Mock SosRequestRepository sosRequestRepository;
  @Mock UserRepository userRepository;
  @Mock MechanicRepository mechanicRepository;
  @Mock MechanicRateRepository mechanicRateRepository;
  @Mock FeedbackMapper feedbackMapper;
  @Mock SosEventProducer sosEventProducer;

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
        Vehicle.builder().id(UUID.randomUUID()).carBrand("Toyota").carModel("Camry").build();
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
  void create_publishesRatingUpdatedEvent() {
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

    when(feedbackRepository.findByMechanicMechanicId(mechanicId))
        .thenReturn(List.of(feedback, secondFeedback));

    when(mechanicRateRepository.findByMechanic(mechanic)).thenReturn(Optional.empty());
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    feedbackService.create(request);

    ArgumentCaptor<RatingUpdateEvent> captor = ArgumentCaptor.forClass(RatingUpdateEvent.class);

    verify(sosEventProducer).publishRatingUpdated(captor.capture());

    RatingUpdateEvent event = captor.getValue();

    assertThat(event.mechanicId()).isEqualTo(mechanicId);
    assertThat(event.avgRate()).isEqualByComparingTo(BigDecimal.valueOf(4.0));
    assertThat(event.ratingCount()).isEqualTo(2);
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
            .vehicle(Vehicle.builder().id(UUID.randomUUID()).build())
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

    assertThat(feedbackService.getBySosRequest(sosId)).isEqualTo(feedbackResponse);
  }

  @Test
  void getBySosRequest_notFound_throwsEntityNotFoundException() {
    when(feedbackRepository.findBySosRequestSosId(sosId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> feedbackService.getBySosRequest(sosId))
        .isInstanceOf(EntityNotFoundException.class)
        .hasMessageContaining(sosId.toString());
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
  void create_doesNotPublishRatingUpdatedEventWhenNoFeedbacks() {
    CreateFeedbackRequest request =
        new CreateFeedbackRequest(
            userId, mechanicId, sosId, BigDecimal.valueOf(4.5), "Create service");

    when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
    when(feedbackRepository.save(any(Feedback.class))).thenReturn(feedback);
    when(feedbackRepository.findByMechanicMechanicId(mechanicId)).thenReturn(List.of());
    when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

    feedbackService.create(request);

    verify(sosEventProducer, never()).publishRatingUpdated(any(RatingUpdateEvent.class));
  }
}
