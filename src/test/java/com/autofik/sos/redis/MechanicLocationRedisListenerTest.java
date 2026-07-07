package com.autofik.sos.redis;

import com.autofik.sos.dto.event.MechanicLocationEvent;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MechanicLocationRedisListenerTest {

    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock ObjectMapper objectMapper;

    @InjectMocks MechanicLocationRedisListener listener;

    private final UUID sosId = UUID.randomUUID();
    private final UUID mechanicId = UUID.randomUUID();

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void onMessage_validPayload_parsesAndForwardsToCorrectWebSocketTopic() throws Exception {
        MechanicLocationEvent event = new MechanicLocationEvent(sosId, mechanicId, 11.5573, 104.9282, Instant.now());
        Message message = mockMessage("mechanic:loc:" + sosId, "{}");
        when(objectMapper.readValue(message.getBody(), MechanicLocationEvent.class)).thenReturn(event);

        listener.onMessage(message, null);

        ArgumentCaptor<MechanicLocationEvent> payload = ArgumentCaptor.forClass(MechanicLocationEvent.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/sos/" + sosId + "/location"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void onMessage_extractsSosIdFromChannelName() throws Exception {
        UUID anotherSosId = UUID.randomUUID();
        MechanicLocationEvent event = new MechanicLocationEvent(anotherSosId, mechanicId, 11.5, 104.9, Instant.now());
        Message message = mockMessage("mechanic:loc:" + anotherSosId, "{}");
        when(objectMapper.readValue(message.getBody(), MechanicLocationEvent.class)).thenReturn(event);

        listener.onMessage(message, null);

        verify(messagingTemplate).convertAndSend(
                eq("/topic/sos/" + anotherSosId + "/location"),
                any(MechanicLocationEvent.class));
    }

    // ── Redis down / bad payload ──────────────────────────────────────────────

    @Test
    void onMessage_malformedJson_doesNotThrow() throws Exception {
        Message message = mockMessage("mechanic:loc:" + sosId, "not-json");
        when(objectMapper.readValue(message.getBody(), MechanicLocationEvent.class))
                .thenThrow(new RuntimeException("parse error"));

        assertThatCode(() -> listener.onMessage(message, null)).doesNotThrowAnyException();
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void onMessage_webSocketFails_doesNotThrow() throws Exception {
        MechanicLocationEvent event = new MechanicLocationEvent(sosId, mechanicId, 11.5, 104.9, Instant.now());
        Message message = mockMessage("mechanic:loc:" + sosId, "{}");
        when(objectMapper.readValue(message.getBody(), MechanicLocationEvent.class)).thenReturn(event);
        doThrow(new RuntimeException("WS broker down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(MechanicLocationEvent.class));

        assertThatCode(() -> listener.onMessage(message, null)).doesNotThrowAnyException();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Message mockMessage(String channel, String body) {
        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn(channel.getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
