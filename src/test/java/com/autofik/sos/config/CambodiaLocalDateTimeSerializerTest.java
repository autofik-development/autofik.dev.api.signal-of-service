package com.autofik.sos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.autofik.sos.dto.response.SosMediaResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class CambodiaLocalDateTimeSerializerTest {

  private final TimeZone original = TimeZone.getDefault();
  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(new SimpleModule().addSerializer(new CambodiaLocalDateTimeSerializer()))
          .build();

  @BeforeEach
  void utcHost() {
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")));
  }

  @AfterEach
  void restore() {
    TimeZone.setDefault(original);
  }

  @Test
  void patternedFieldMovesUtcWallClockToPlus7() {
    // 2026-10-20 08:05:06 on a UTC host is 15:05:06 in Cambodia.
    var media =
        new SosMediaResponse(null, null, null, null, null, LocalDateTime.of(2026, 10, 20, 8, 5, 6));
    assertThat(mapper.writeValueAsString(media))
        .contains("\"uploadedAt\":\"Oct 20 2026, 3:05:06 PM\"");
  }

  @Test
  void unannotatedValueIsIsoAtPlus7() {
    String json = mapper.writeValueAsString(Map.of("at", LocalDateTime.of(2026, 10, 20, 8, 5, 6)));
    assertThat(json).isEqualTo("{\"at\":\"2026-10-20T15:05:06\"}");
  }

  @Test
  void hostAlreadyAtPlus7IsUnchanged() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Phnom_Penh"));
    String json = mapper.writeValueAsString(Map.of("at", LocalDateTime.of(2026, 10, 20, 15, 5, 6)));
    assertThat(json).isEqualTo("{\"at\":\"2026-10-20T15:05:06\"}");
  }
}
