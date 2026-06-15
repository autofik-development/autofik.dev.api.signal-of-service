package com.autofik.sos.service;

import com.autofik.sos.domain.entity.Feedback;
import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.entity.Vehicle;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.request.CreateFeedbackRequest;
import com.autofik.sos.dto.response.FeedbackResponse;
import com.autofik.sos.mapper.FeedbackMapper;
import com.autofik.sos.repository.FeedbackRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.repository.UserRepository;
import com.autofik.sos.service.impl.FeedbackServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

    @Mock FeedbackRepository feedbackRepository;
    @Mock SosRequestRepository sosRequestRepository;
    @Mock UserRepository userRepository;
    @Mock MechanicRepository mechanicRepository;
    @Mock FeedbackMapper feedbackMapper;

    @InjectMocks FeedbackServiceImpl feedbackService;

    private final UUID userId     = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();
    private final UUID sosId      = UUID.randomUUID();
    private final UUID feedbackId = UUID.randomUUID();

    private User user;
    private Mechanic mechanic;
    private SosRequest completedSos;
    private Feedback feedback;
    private FeedbackResponse feedbackResponse;

    @BeforeEach
    void setUp() {
        user = User.builder().id(userId).name("Alice").hashPassword("h").build();
        mechanic = Mechanic.builder().mechanicId(mechanicId).name("Bob").phone("081")
                .email("bob@fix.com").hashPassword("h").ratingAvg(BigDecimal.ZERO).build();
        Vehicle vehicle = Vehicle.builder().vehicleId(UUID.randomUUID()).plateNumber("ABC")
                .brand("Toyota").model("Camry").year(2020).build();
        completedSos = SosRequest.builder().sosId(sosId).problemType("Flat Tire")
                .status(SosStatus.COMPLETED).user(user).vehicle(vehicle).build();
        feedback = Feedback.builder().id(feedbackId).rating(BigDecimal.valueOf(4.5))
                .user(user).mechanic(mechanic).sosRequest(completedSos).build();
        feedbackResponse = new FeedbackResponse(feedbackId, null, BigDecimal.valueOf(4.5), null, null, null);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_completedSos_savesAndRecalculatesMechanicRating() {
        CreateFeedbackRequest request = new CreateFeedbackRequest(
                userId, mechanicId, sosId, BigDecimal.valueOf(4.5), "Great service");
        Feedback secondFeedback = Feedback.builder().id(UUID.randomUUID())
                .rating(BigDecimal.valueOf(3.5)).user(user).mechanic(mechanic).sosRequest(completedSos).build();

        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(feedbackRepository.save(any(Feedback.class))).thenReturn(feedback);
        // Two feedbacks → average = (4.5 + 3.5) / 2 = 4.0
        when(feedbackRepository.findByMechanicMechanicId(mechanicId))
                .thenReturn(List.of(feedback, secondFeedback));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
        when(feedbackMapper.toResponse(feedback)).thenReturn(feedbackResponse);

        FeedbackResponse result = feedbackService.create(request);

        assertThat(result).isEqualTo(feedbackResponse);
        assertThat(mechanic.getRatingAvg()).isEqualByComparingTo(BigDecimal.valueOf(4.0));
        verify(mechanicRepository).save(mechanic);
    }

    @Test
    void create_savesCorrectFields() {
        CreateFeedbackRequest request = new CreateFeedbackRequest(
                userId, mechanicId, sosId, BigDecimal.valueOf(5.0), "Excellent");
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(mechanicRepository.findById(mechanicId)).thenReturn(Optional.of(mechanic));
        when(feedbackRepository.save(any(Feedback.class))).thenReturn(feedback);
        when(feedbackRepository.findByMechanicMechanicId(mechanicId)).thenReturn(List.of(feedback));
        when(mechanicRepository.save(mechanic)).thenReturn(mechanic);
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
        SosRequest pendingSos = SosRequest.builder().sosId(sosId).problemType("X")
                .status(SosStatus.PENDING).user(user).vehicle(
                        Vehicle.builder().vehicleId(UUID.randomUUID()).plateNumber("X")
                                .brand("X").model("X").year(2020).build()).build();
        CreateFeedbackRequest request = new CreateFeedbackRequest(
                userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(pendingSos));

        assertThatThrownBy(() -> feedbackService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void create_userNotFound_throwsEntityNotFoundException() {
        CreateFeedbackRequest request = new CreateFeedbackRequest(
                userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(completedSos));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> feedbackService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    @Test
    void create_mechanicNotFound_throwsEntityNotFoundException() {
        CreateFeedbackRequest request = new CreateFeedbackRequest(
                userId, mechanicId, sosId, BigDecimal.valueOf(4.0), null);
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
        when(feedbackRepository.findByMechanicMechanicId(mechanicId)).thenReturn(List.of(feedback));
        when(feedbackMapper.toResponseList(List.of(feedback))).thenReturn(List.of(feedbackResponse));

        assertThat(feedbackService.getByMechanic(mechanicId)).containsExactly(feedbackResponse);
    }
}
