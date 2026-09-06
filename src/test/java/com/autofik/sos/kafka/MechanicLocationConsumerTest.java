package com.autofik.sos.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import com.autofik.sos.kafka.consumer.MechanicLocationConsumer;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class MechanicLocationConsumerTest {

  @Mock SimpMessagingTemplate messagingTemplate;

  @InjectMocks MechanicLocationConsumer consumer;

  private final UUID sosId = UUID.randomUUID();
  private final UUID mechanicId = UUID.randomUUID();

  // ── happy path (Redis was down, Kafka fallback active) ────────────────────

  @Test
  void consume_validEvent_forwardsToCorrectWebSocketTopic() {
    MechanicLocationEvent event =
        new MechanicLocationEvent(
            sosId, mechanicId, 11.5573, 104.9282, OffsetDateTime.now(), null, null, null, null);

    consumer.consume(event);

    ArgumentCaptor<MechanicLocationEvent> payload =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(messagingTemplate)
        .convertAndSend(eq("/topic/sos/" + sosId + "/location"), payload.capture());
    assertThat(payload.getValue()).isEqualTo(event);
  }

  @Test
  void consume_forwardsExactCoordinates() {
    double lat = 13.7563;
    double lng = 100.5018;
    MechanicLocationEvent event =
        new MechanicLocationEvent(
            sosId, mechanicId, lat, lng, OffsetDateTime.now(), null, null, null, null);

    consumer.consume(event);

    ArgumentCaptor<MechanicLocationEvent> captor =
        ArgumentCaptor.forClass(MechanicLocationEvent.class);
    verify(messagingTemplate).convertAndSend(anyString(), captor.capture());
    assertThat(captor.getValue().latitude()).isEqualTo(lat);
    assertThat(captor.getValue().longitude()).isEqualTo(lng);
    assertThat(captor.getValue().mechanicId()).isEqualTo(mechanicId);
  }

  // ── worst-case: WebSocket is also down ────────────────────────────────────

  @Test
  void consume_webSocketFails_doesNotThrow_kafkaOffsetAdvances() {
    MechanicLocationEvent event =
        new MechanicLocationEvent(
            sosId, mechanicId, 11.5, 104.9, OffsetDateTime.now(), null, null, null, null);
    doThrow(new RuntimeException("WS broker unavailable"))
        .when(messagingTemplate)
        .convertAndSend(anyString(), any(MechanicLocationEvent.class));

    // No exception = Kafka commits the offset and moves on rather than retrying forever
    assertThatCode(() -> consumer.consume(event)).doesNotThrowAnyException();
  }
}
