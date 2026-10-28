package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;

class KafkaClusterIdTest {

  private KafkaConfig.ObservedKafkaTemplate<String, Object> template() {
    return new KafkaConfig.ObservedKafkaTemplate<>(
        new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", "localhost:1")));
  }

  @Test
  void withoutAnAdmin_theSpanJustHasNoClusterName() {
    assertThat(template().clusterId()).isNull();
  }

  @Test
  void withAnAdmin_usesTheNameItAlreadyHas() {
    KafkaAdmin admin = mock(KafkaAdmin.class);
    when(admin.getClusterId()).thenReturn("cluster-1");
    StaticApplicationContext ctx = new StaticApplicationContext();
    ctx.getBeanFactory().registerSingleton("kafkaAdmin", admin);
    var template = template();
    template.setApplicationContext(ctx);
    template.afterSingletonsInstantiated();

    assertThat(template.clusterId()).isEqualTo("cluster-1");
  }
}
