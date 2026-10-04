package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Topic names are the contract with every other service; partitions and replicas are the durability
 * and parallelism settings. Both are easy to change by accident in a copy-pasted bean.
 */
class KafkaTopicsTest {

  private List<NewTopic> topics(int partitions, short replicas) {
    KafkaConfig c = new KafkaConfig(new KafkaProperties());
    ReflectionTestUtils.setField(c, "partitionCount", partitions);
    ReflectionTestUtils.setField(c, "replicationFactor", replicas);
    return List.of(
        c.sosCreatedTopic(),
        c.sosBidCreatedTopic(),
        c.sosBidAcceptedTopic(),
        c.sosBidRejectedTopic(),
        c.sosBidCancelledTopic(),
        c.sosStatusUpdatedTopic(),
        c.sosDlqTopic(),
        c.mechanicLocationUpdatedTopic(),
        c.sosNotificationTopic(),
        c.sosMediaUploadedTopic());
  }

  @Test
  void everyTopicTheProducerUses_isDeclared_withTheDocumentedName() {
    assertThat(topics(3, (short) 1))
        .extracting(NewTopic::name)
        .containsExactlyInAnyOrder(
            "sos.created",
            "sos.bid.created",
            "sos.bid.accepted",
            "sos.bid.rejected",
            "sos.bid.cancelled",
            "sos.status.updated",
            "sos.dlq",
            "mechanic.location.updated",
            "sos.notification",
            "sos.media.uploaded");
  }

  @Test
  void partitionsAndReplicas_followTheConfiguration() {
    for (NewTopic t : topics(6, (short) 3)) {
      assertThat(t.replicationFactor()).as(t.name()).isEqualTo((short) 3);
      if (!t.name().equals("sos.dlq")) {
        assertThat(t.numPartitions()).as(t.name()).isEqualTo(6);
      }
    }
  }

  @Test
  void deadLetterTopic_isAlwaysOnePartition_becauseTheRecovererTargetsPartitionZero() {
    NewTopic dlq =
        topics(12, (short) 1).stream()
            .filter(t -> t.name().equals("sos.dlq"))
            .findFirst()
            .orElseThrow();
    assertThat(dlq.numPartitions()).isEqualTo(1);
  }
}
