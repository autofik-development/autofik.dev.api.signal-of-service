package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaTopologyCheckTest {

  @Test
  void singleBroker_isTheDevSetup_andNeverWarns() {
    assertThat(KafkaTopologyCheck.problems(1, 1, Map.of("sos.created", 1))).isEmpty();
  }

  @Test
  void cluster_withReplicationFactorStillOne_isFlagged() {
    var problems = KafkaTopologyCheck.problems(3, 1, Map.of());

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("3 brokers").contains("KAFKA_TOPIC_REPLICATION_FACTOR=3");
  }

  /** The easy one to miss: the setting is fixed but a topic created earlier keeps one replica. */
  @Test
  void cluster_withCorrectSetting_butAnOldSingleReplicaTopic_isFlagged() {
    var problems = KafkaTopologyCheck.problems(3, 3, Map.of("sos.dlq", 1, "sos.created", 3));

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("'sos.dlq'").contains("kafka-reassign-partitions");
  }

  @Test
  void twoBrokerCluster_wantsTwoReplicas_notThree() {
    assertThat(KafkaTopologyCheck.problems(2, 2, Map.of("sos.created", 2))).isEmpty();
    assertThat(KafkaTopologyCheck.problems(2, 1, Map.of())).hasSize(1);
  }

  @Test
  void healthyCluster_isQuiet() {
    assertThat(KafkaTopologyCheck.problems(3, 3, Map.of("sos.created", 3, "sos.dlq", 3))).isEmpty();
  }
}
