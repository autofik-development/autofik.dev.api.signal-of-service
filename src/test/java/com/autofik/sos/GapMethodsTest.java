package com.autofik.sos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.autofik.sos.domain.entity.BidSos;
import com.autofik.sos.domain.entity.SosMedia;
import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.dto.event.SosNotificationEvent;
import com.autofik.sos.dto.request.CreateUserRequest;
import com.autofik.sos.exception.GlobalExceptionHandler;
import com.autofik.sos.repository.SosOfferRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Small hooks and shims that nothing else happens to call. */
class GapMethodsTest {

  @Test
  void persistenceHooks_stampTheTimes() {
    SosRequest sos = SosRequest.builder().build();
    ReflectionTestUtils.invokeMethod(sos, "onCreate");
    assertThat(sos.getCreatedAt()).isNotNull();
    sos.setUpdatedAt(null);
    ReflectionTestUtils.invokeMethod(sos, "onUpdate");
    assertThat(sos.getUpdatedAt()).isNotNull();

    SosMedia media = SosMedia.builder().build();
    ReflectionTestUtils.invokeMethod(media, "onCreate");
    assertThat(media.getUploadedAt()).isNotNull();

    BidSos bid = BidSos.builder().build();
    ReflectionTestUtils.invokeMethod(bid, "onCreate");
    assertThat(bid.getBidAt()).isNotNull();
  }

  @Test
  void sevenArgumentNotificationConstructor_leavesTheEstimateEmpty() {
    SosNotificationEvent e =
        new SosNotificationEvent(
            "T", UUID.randomUUID(), "USER", UUID.randomUUID(), "t", "b", List.of());
    assertThat(e.estimate()).isNull();
    assertThat(e.recipientIds()).isNull();
  }

  @Test
  void requestRecords_holdTheirFields() {
    assertThat(new CreateUserRequest("n", "p").name()).isEqualTo("n");
  }

  @Test
  void typeMismatchAndBlankPathVariable_areFixedTexts() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();
    MethodArgumentTypeMismatchException mismatch = mock(MethodArgumentTypeMismatchException.class);
    when(mismatch.getName()).thenReturn("sosId");
    MissingPathVariableException missing = mock(MissingPathVariableException.class);
    when(missing.getVariableName()).thenReturn("sosId");

    assertThat(handler.handleTypeMismatch(mismatch).message()).contains("'sosId'");
    assertThat(handler.handleMissingPathVariable(missing).message()).contains("'sosId'");
  }

  @Test
  void recordOfferedDefault_passesTheIdsAsAnArray() {
    SosOfferRepository repo = mock(SosOfferRepository.class, CALLS_REAL_METHODS);
    UUID sos = UUID.randomUUID();
    UUID m = UUID.randomUUID();
    org.mockito.Mockito.doReturn(1).when(repo).recordOffered(sos, new UUID[] {m});

    repo.recordOffered(sos, List.of(m));

    verify(repo).recordOffered(sos, new UUID[] {m});
  }
}
