package com.autofik.sos.service;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.domain.entity.User;
import com.autofik.sos.domain.enums.SosStatus;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.event.SosStatusUpdatedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.autofik.sos.repository.SosRequestRepository;
import com.autofik.sos.service.impl.SosExpiryScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The expiry claim is the whole point of this class: whoever wins the conditional update is the
 * one — and the only one — that tells the driver nobody came.
 */
@ExtendWith(MockitoExtension.class)
class SosExpirySchedulerTest {

    @Mock SosRequestRepository sosRequestRepository;
    @Mock SosEventProducer sosEventProducer;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock TaskScheduler taskScheduler;

    @InjectMocks SosExpiryScheduler scheduler;

    private final UUID sosId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private SosRequest expiredSos;

    @BeforeEach
    void setUp() {
        User user = User.builder().userId(userId).name("Alice").build();
        expiredSos = SosRequest.builder().sosId(sosId).problemType("FLAT_TIRE")
                .status(SosStatus.EXPIRED).user(user).build();
    }

    @Test
    void expire_claimWon_publishesTheExpiryOnce() {
        when(sosRequestRepository.compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED))
                .thenReturn(1);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(expiredSos));

        scheduler.expire(sosId);

        ArgumentCaptor<SosStatusUpdatedEvent> captor = ArgumentCaptor.forClass(SosStatusUpdatedEvent.class);
        verify(sosEventProducer).publishStatusUpdated(captor.capture());
        assertThat(captor.getValue().fromStatus()).isEqualTo(SosStatus.PENDING);
        assertThat(captor.getValue().toStatus()).isEqualTo(SosStatus.EXPIRED);
        verify(sosEventProducer).publishNotification(any(SosNotificationEvent.class));
        verify(messagingTemplate).convertAndSendToUser(eq(userId.toString()), anyString(), any());
    }

    // A bid accepted in the last second of the window commits before the timer fires. The claim
    // returns 0, and the driver whose mechanic is already on the way hears nothing about expiry.
    @Test
    void expire_claimLost_publishesNothing() {
        when(sosRequestRepository.compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED))
                .thenReturn(0);

        scheduler.expire(sosId);

        verify(sosRequestRepository, never()).findById(any());
        verifyNoInteractions(sosEventProducer, messagingTemplate);
    }

    // Retries left decides whether the driver is offered "try again" or told to call support.
    @Test
    void expire_lastAttempt_tellsTheDriverToCallSupport() {
        expiredSos.setRebroadcastCount(SosRequest.MAX_REBROADCAST);
        when(sosRequestRepository.compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED))
                .thenReturn(1);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(expiredSos));

        scheduler.expire(sosId);

        ArgumentCaptor<SosNotificationEvent> notification = ArgumentCaptor.forClass(SosNotificationEvent.class);
        verify(sosEventProducer).publishNotification(notification.capture());
        assertThat(notification.getValue().body()).contains("call support");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), anyString(), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) payload.getValue();
        assertThat(message).containsEntry("retriesLeft", false).containsEntry("attemptsLeft", 0);
    }

    // Every instance runs the startup sweep; the claim is what keeps the notifications single.
    @Test
    void reconcileOnStartup_expiresOverdueAndRearmsTheRest() {
        SosRequest overdue = SosRequest.builder().sosId(sosId).status(SosStatus.PENDING)
                .biddingExpiresAt(LocalDateTime.now().minusMinutes(5)).build();
        UUID stillOpenId = UUID.randomUUID();
        SosRequest stillOpen = SosRequest.builder().sosId(stillOpenId).status(SosStatus.PENDING)
                .biddingExpiresAt(LocalDateTime.now().plusMinutes(2)).build();

        when(sosRequestRepository.findByStatusAndBiddingExpiresAtBefore(eq(SosStatus.PENDING), any()))
                .thenReturn(List.of(overdue));
        when(sosRequestRepository.findByStatusAndBiddingExpiresAtAfter(eq(SosStatus.PENDING), any()))
                .thenReturn(List.of(stillOpen));
        when(sosRequestRepository.compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED))
                .thenReturn(1);
        when(sosRequestRepository.findById(sosId)).thenReturn(Optional.of(expiredSos));

        scheduler.reconcileOnStartup();

        verify(sosRequestRepository).compareAndSetStatus(sosId, SosStatus.PENDING, SosStatus.EXPIRED);
        verify(taskScheduler).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    // No expiry instant means no timer to arm — a request that has already been answered.
    @Test
    void schedule_withoutExpiryInstant_armsNothing() {
        scheduler.schedule(SosRequest.builder().sosId(sosId).build());

        verifyNoInteractions(taskScheduler);
    }
}
