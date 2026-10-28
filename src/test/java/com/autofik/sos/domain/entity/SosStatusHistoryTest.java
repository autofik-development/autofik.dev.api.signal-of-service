package com.autofik.sos.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.domain.enums.SosStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The audit row's own behaviour: its creation stamp and a toString that never loads a relation. */
class SosStatusHistoryTest {

  @Test
  void onCreate_stampsTheChangeTime() {
    SosStatusHistory row = new SosStatusHistory();

    row.onCreate();

    assertThat(row.getChangeAt()).isNotNull();
  }

  @Test
  void toString_namesTheRelatedIds_withoutTouchingTheEntities() {
    UUID mechanicId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID sosId = UUID.randomUUID();
    SosStatusHistory row =
        SosStatusHistory.builder()
            .id(UUID.randomUUID())
            .fromStatus(SosStatus.ACCEPTED)
            .toStatus(SosStatus.ON_THE_WAY)
            .proposedBy(Mechanic.builder().mechanicId(mechanicId).build())
            .approvedBy(User.builder().userId(userId).build())
            .sosRequest(SosRequest.builder().sosId(sosId).build())
            .build();

    assertThat(row.toString())
        .contains(mechanicId.toString(), userId.toString(), sosId.toString())
        .contains("ACCEPTED")
        .contains("ON_THE_WAY");
  }

  @Test
  void toString_withNoRelations_printsNulls() {
    SosStatusHistory row = SosStatusHistory.builder().fromStatus(SosStatus.PENDING).build();

    assertThat(row.toString()).contains("PENDING").contains("null");
  }
}
