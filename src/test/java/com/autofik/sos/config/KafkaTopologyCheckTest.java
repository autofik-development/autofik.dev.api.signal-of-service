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

  /** A cluster that cannot be reached is logged, never thrown: the check must not stop the boot. */
  @Test
  void check_withAnUnusableAdminConfiguration_doesNotThrow() {
    org.springframework.kafka.core.KafkaAdmin admin =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaAdmin.class);
    org.mockito.Mockito.when(admin.getConfigurationProperties()).thenReturn(Map.of());

    new KafkaTopologyCheck(admin).check();
  }

  private static org.apache.kafka.clients.admin.Admin clusterOf(int brokers) throws Exception {
    org.apache.kafka.clients.admin.Admin client =
        org.mockito.Mockito.mock(org.apache.kafka.clients.admin.Admin.class);
    var cluster =
        org.mockito.Mockito.mock(org.apache.kafka.clients.admin.DescribeClusterResult.class);
    var nodes = new java.util.ArrayList<org.apache.kafka.common.Node>();
    for (int i = 0; i < brokers; i++) nodes.add(new org.apache.kafka.common.Node(i, "h", 9092));
    org.mockito.Mockito.when(cluster.nodes())
        .thenReturn(org.apache.kafka.common.KafkaFuture.completedFuture(nodes));
    org.mockito.Mockito.when(client.describeCluster()).thenReturn(cluster);
    return client;
  }

  private static void runCheck(org.apache.kafka.clients.admin.Admin client) {
    org.springframework.kafka.core.KafkaAdmin admin =
        org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaAdmin.class);
    org.mockito.Mockito.when(admin.getConfigurationProperties()).thenReturn(Map.of());
    try (var mocked = org.mockito.Mockito.mockStatic(org.apache.kafka.clients.admin.Admin.class)) {
      mocked
          .when(
              () ->
                  org.apache.kafka.clients.admin.Admin.create(
                      org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
          .thenReturn(client);
      new KafkaTopologyCheck(admin).check();
    }
  }

  @Test
  void check_singleBroker_returnsWithoutDescribingTopics() throws Exception {
    var client = clusterOf(1);

    runCheck(client);

    org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
        .describeTopics(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any());
  }

  @Test
  void check_cluster_readsTopicReplicas_andSurvivesATopicThatDoesNotExistYet() throws Exception {
    var client = clusterOf(3);
    var info =
        new org.apache.kafka.common.TopicPartitionInfo(
            0, null, java.util.List.of(), java.util.List.of());
    var described =
        org.mockito.Mockito.mock(org.apache.kafka.clients.admin.DescribeTopicsResult.class);
    var td =
        new org.apache.kafka.clients.admin.TopicDescription(
            "sos.created", false, java.util.List.of(info));
    @SuppressWarnings("unchecked")
    org.apache.kafka.common.KafkaFuture<org.apache.kafka.clients.admin.TopicDescription> missing =
        org.mockito.Mockito.mock(org.apache.kafka.common.KafkaFuture.class);
    org.mockito.Mockito.when(
            missing.get(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenThrow(
            new java.util.concurrent.ExecutionException(new RuntimeException("unknown topic")));
    org.mockito.Mockito.when(described.topicNameValues())
        .thenReturn(
            Map.of(
                "sos.created",
                org.apache.kafka.common.KafkaFuture.completedFuture(td),
                "sos.dlq",
                missing));
    org.mockito.Mockito.when(
            client.describeTopics(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any()))
        .thenReturn(described);

    runCheck(client);

    org.mockito.Mockito.verify(client)
        .describeTopics(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any());
  }
}
