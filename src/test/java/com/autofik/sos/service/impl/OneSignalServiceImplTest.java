package com.autofik.sos.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.dto.event.OneSignalPushFailedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.onesignal.client.ApiException;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.model.Notification;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OneSignalServiceImplTest {

  @Mock private DefaultApi oneSignalApi;

  @Mock private SosEventProducer sosEventProducer;

  @InjectMocks private OneSignalServiceImpl oneSignalService;

  @Test
  void shouldNotPublishDlqWhenPushSucceeds() throws ApiException {
    UUID recipientId = UUID.randomUUID();

    oneSignalService.send(recipientId, "Test title", "Test body", Map.of("type", "TEST"), null);

    verify(oneSignalApi).createNotification(any(Notification.class));
    verify(sosEventProducer, never()).publishOneSignalPushFailed(any());
  }

  @Test
  void shouldPublishDlqWhenOneSignalApiExceptionOccurs() throws ApiException {
    UUID recipientId = UUID.randomUUID();

    ApiException exception = org.mockito.Mockito.mock(ApiException.class);
    when(exception.getCode()).thenReturn(400);
    when(exception.getResponseBody()).thenReturn("OneSignal push failed");

    doThrow(exception).when(oneSignalApi).createNotification(any(Notification.class));

    oneSignalService.send(recipientId, "Test title", "Test body", Map.of("type", "TEST"), null);

    ArgumentCaptor<OneSignalPushFailedEvent> captor =
        ArgumentCaptor.forClass(OneSignalPushFailedEvent.class);

    verify(sosEventProducer).publishOneSignalPushFailed(captor.capture());

    OneSignalPushFailedEvent event = captor.getValue();

    assertThat(event.recipientId()).isEqualTo(recipientId);
    assertThat(event.status()).isEqualTo(400);
    assertThat(event.error()).isEqualTo("OneSignal push failed");
    assertThat(event.failedAt()).isNotNull();
  }

  @Test
  void shouldPublishDlqWhenUnexpectedExceptionOccurs() throws ApiException {
    UUID recipientId = UUID.randomUUID();
    doThrow(new RuntimeException("Unexpected error"))
        .when(oneSignalApi)
        .createNotification(any(Notification.class));

    oneSignalService.send(recipientId, "Test title", "Test body", Map.of("type", "TEST"), null);

    ArgumentCaptor<OneSignalPushFailedEvent> captor =
        ArgumentCaptor.forClass(OneSignalPushFailedEvent.class);

    verify(sosEventProducer).publishOneSignalPushFailed(captor.capture());

    OneSignalPushFailedEvent event = captor.getValue();

    assertThat(event.recipientId()).isEqualTo(recipientId);
    assertThat(event.status()).isNull();
    assertThat(event.error()).isEqualTo("Unexpected error");
    assertThat(event.failedAt()).isNotNull();
  }

  /** OneSignal takes at most 2,000 aliases per request; a bigger batch is split, none dropped. */
  @Test
  void sendToMany_targetsAllRecipientsInOneRequest_chunkedAt2000() throws ApiException {
    List<UUID> recipients =
        java.util.stream.Stream.generate(UUID::randomUUID).limit(2_500).toList();

    oneSignalService.sendToMany(recipients, "t", "b", Map.of("type", "BID_REJECTED"), null);

    ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
    verify(oneSignalApi, times(2)).createNotification(sent.capture());
    List<String> targeted =
        sent.getAllValues().stream()
            .flatMap(n -> n.getIncludeAliases().get("external_id").stream())
            .toList();
    assertThat(sent.getAllValues().get(0).getIncludeAliases().get("external_id")).hasSize(2_000);
    assertThat(targeted)
        .containsExactlyElementsOf(recipients.stream().map(UUID::toString).toList());
  }

  /** A failed batch reports every recipient in it, same as a failed single push does. */
  @Test
  void sendToMany_failure_reportsEachRecipient() throws ApiException {
    List<UUID> recipients = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    doThrow(new RuntimeException("boom")).when(oneSignalApi).createNotification(any());

    oneSignalService.sendToMany(recipients, "t", "b", Map.of(), null);

    ArgumentCaptor<OneSignalPushFailedEvent> failed =
        ArgumentCaptor.forClass(OneSignalPushFailedEvent.class);
    verify(sosEventProducer, times(3)).publishOneSignalPushFailed(failed.capture());
    assertThat(failed.getAllValues())
        .extracting(OneSignalPushFailedEvent::recipientId)
        .containsExactlyElementsOf(recipients);
  }

  /** The key reaches OneSignal, which drops a repeat with the same key for 30 days. */
  @Test
  void send_passesTheIdempotencyKeyToOneSignal() throws ApiException {
    UUID key = UUID.randomUUID();

    oneSignalService.send(UUID.randomUUID(), "t", "b", Map.of(), key);

    ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
    verify(oneSignalApi).createNotification(sent.capture());
    assertThat(sent.getValue().getIdempotencyKey()).isEqualTo(key.toString());
  }

  /**
   * Each chunk is a separate notification. With one shared key OneSignal would drop every chunk
   * after the first, so each chunk gets its own, and the same batch sent again gets the same keys.
   */
  @Test
  void sendToMany_givesEachChunkItsOwnStableKey() throws ApiException {
    List<UUID> recipients =
        java.util.stream.Stream.generate(UUID::randomUUID).limit(4_500).toList();
    UUID key = UUID.randomUUID();

    oneSignalService.sendToMany(recipients, "t", "b", Map.of(), key);
    oneSignalService.sendToMany(recipients, "t", "b", Map.of(), key);

    ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
    verify(oneSignalApi, times(6)).createNotification(sent.capture());
    List<String> keys = sent.getAllValues().stream().map(Notification::getIdempotencyKey).toList();
    assertThat(keys.subList(0, 3)).doesNotHaveDuplicates().startsWith(key.toString());
    assertThat(keys.subList(3, 6)).isEqualTo(keys.subList(0, 3));
  }

  @Test
  void noKey_sendsWithoutOne() throws ApiException {
    oneSignalService.send(UUID.randomUUID(), "t", "b", Map.of(), null);

    ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
    verify(oneSignalApi).createNotification(sent.capture());
    assertThat(sent.getValue().getIdempotencyKey()).isNull();
  }

  @Test
  void noData_andEmptyData_sendWithoutADataBlock() throws ApiException {
    oneSignalService.send(UUID.randomUUID(), "t", "b", null, null);
    oneSignalService.send(UUID.randomUUID(), "t", "b", Map.of(), null);

    ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
    verify(oneSignalApi, times(2)).createNotification(sent.capture());
    assertThat(sent.getAllValues()).allMatch(n -> n.getData() == null);
  }

  @Test
  void sendToMany_apiRejection_isReportedForEveryRecipient() throws ApiException {
    ApiException rejected = org.mockito.Mockito.mock(ApiException.class);
    when(rejected.getCode()).thenReturn(400);
    doThrow(rejected).when(oneSignalApi).createNotification(any(Notification.class));

    oneSignalService.sendToMany(
        List.of(UUID.randomUUID(), UUID.randomUUID()), "t", "b", Map.of("type", "X"), null);

    verify(sosEventProducer, times(2)).publishOneSignalPushFailed(any());
  }
}
