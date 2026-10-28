package com.autofik.sos.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.kafka.consumer.NotificationConsumer;
import com.autofik.sos.service.OneSignalService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationConsumerBranchesTest {

  private final OneSignalService push = mock(OneSignalService.class);

  private SosNotificationEvent event(UUID one, List<UUID> many) {
    return new SosNotificationEvent(
        "BID_RECEIVED", one, "USER", UUID.randomUUID(), "t", "b", null, null, many);
  }

  @Test
  void ownedPoolIsShutDown_andAPlainExecutorHasNothingToShutDown() {
    new NotificationConsumer(push, 1, 1).shutdown();
    new NotificationConsumer(push, Runnable::run).shutdown();
  }

  @Test
  void nullTombstone_isIgnored() {
    new NotificationConsumer(push, Runnable::run).consume(null, "t", 0, 0L, 0L);
    verify(push, never()).sendToMany(any(), any(), any(), any(), any());
  }

  @Test
  void anEmptyRecipientList_fallsBackToTheSingleRecipient() {
    new NotificationConsumer(push, Runnable::run)
        .consume(event(UUID.randomUUID(), List.of()), "t", 0, 0L, 0L);
    verify(push, never()).sendToMany(any(), any(), any(), any(), any());
  }

  @Test
  void aFanOutWithNobodyNamed_sendsNothing() {
    new NotificationConsumer(push, Runnable::run).consume(event(null, null), "t", 0, 0L, 0L);
    verify(push, never()).sendToMany(any(), any(), any(), any(), any());
  }
}
