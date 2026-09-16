package com.autofik.sos.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.entity.SosRequest;
import com.autofik.sos.dto.response.SosRequestResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

// Only the durationSeconds default method needs a direct test — the rest of SosMapper is
// generated MapStruct field mapping, already exercised indirectly wherever toResponse is used.
class SosMapperDurationTest {

  private final SosMapper mapper =
      new SosMapper() {
        @Override
        public SosRequestResponse toResponse(SosRequest sosRequest) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<SosRequestResponse> toResponseList(List<SosRequest> sosRequests) {
          throw new UnsupportedOperationException();
        }
      };

  @Test
  void durationSeconds_bothTimestampsSet_returnsGap() {
    LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
    SosRequest sos =
        SosRequest.builder().jobStartedAt(start).jobCompletedAt(start.plusMinutes(24)).build();

    assertThat(mapper.durationSeconds(sos)).isEqualTo(24 * 60);
  }

  @Test
  void durationSeconds_notStarted_returnsNull() {
    SosRequest sos = SosRequest.builder().build();

    assertThat(mapper.durationSeconds(sos)).isNull();
  }

  @Test
  void durationSeconds_startedNotCompleted_returnsNull() {
    SosRequest sos = SosRequest.builder().jobStartedAt(LocalDateTime.now()).build();

    assertThat(mapper.durationSeconds(sos)).isNull();
  }
}
