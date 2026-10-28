package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class CambodiaSerializerContextualTest {

  record Plain(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime at) {}

  record Localised(@JsonFormat(pattern = "MMM", locale = "en") LocalDateTime at) {}

  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(new SimpleModule().addSerializer(new CambodiaLocalDateTimeSerializer()))
          .build();

  @Test
  void aFormatWithoutAPattern_keepsTheIsoDefault() {
    String json = mapper.writeValueAsString(new Plain(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
    assertThat(json).contains("2026-01-");
  }

  @Test
  void aFormatWithAnExplicitLocale_isUsed() {
    String json = mapper.writeValueAsString(new Localised(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
    assertThat(json).contains("Jan");
  }
}
