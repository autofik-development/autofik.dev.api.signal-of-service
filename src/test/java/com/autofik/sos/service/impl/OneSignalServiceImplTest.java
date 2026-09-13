package com.autofik.sos.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.dto.event.OneSignalPushFailedEvent;
import com.autofik.sos.kafka.producer.SosEventProducer;
import com.onesignal.client.ApiException;
import com.onesignal.client.api.DefaultApi;
import com.onesignal.client.model.Notification;
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

	@Mock
	private DefaultApi oneSignalApi;

	@Mock
	private SosEventProducer sosEventProducer;

	@InjectMocks
	private OneSignalServiceImpl oneSignalService;

	@Test
	void shouldNotPublishDlqWhenPushSucceeds() throws ApiException {
		UUID recipientId = UUID.randomUUID();

		oneSignalService.send(
				recipientId,
				"Test title",
				"Test body",
				Map.of("type", "TEST"));

		verify(oneSignalApi).createNotification(any(Notification.class));
		verify(sosEventProducer, never()).publishOneSignalPushFailed(any());

	}

	@Test
	void shouldPublishDlqWhenOneSignalApiExceptionOccurs() throws ApiException {
		UUID recipientId = UUID.randomUUID();

		ApiException exception = org.mockito.Mockito.mock(ApiException.class);
		when(exception.getCode()).thenReturn(400);
		when(exception.getResponseBody()).thenReturn("OneSignal push failed");

		doThrow(exception)
				.when(oneSignalApi)
				.createNotification(any(Notification.class));

		oneSignalService.send(
				recipientId,
				"Test title",
				"Test body",
				Map.of("type", "TEST"));

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

		oneSignalService.send(
				recipientId,
				"Test title",
				"Test body",
				Map.of("type", "TEST"));

		ArgumentCaptor<OneSignalPushFailedEvent> captor =
				ArgumentCaptor.forClass(OneSignalPushFailedEvent.class);

		verify(sosEventProducer).publishOneSignalPushFailed(captor.capture());

		OneSignalPushFailedEvent event = captor.getValue();

		assertThat(event.recipientId()).isEqualTo(recipientId);
		assertThat(event.status()).isNull();
		assertThat(event.error()).isEqualTo("Unexpected error");
		assertThat(event.failedAt()).isNotNull();

	}
}