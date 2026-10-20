package com.autofik.sos.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Writes every {@link LocalDateTime} in a REST or WebSocket body as Cambodian local time (+07:00).
 *
 * <p>A LocalDateTime carries no zone: the service stores {@code LocalDateTime.now()}, the wall
 * clock of the JVM's default zone (UTC in a container). This reads the value in that zone and moves
 * it to Asia/Phnom_Penh, so the same code shows the right time on a UTC host and is a no-op on a
 * host already at +07:00. Stored data is untouched; only what clients see changes.
 *
 * <p>A field's own {@code @JsonFormat(pattern = ...)} is still honoured; with none, the value is
 * written as ISO-8601 local date-time, as before. Kafka has its own ObjectMapper and is not
 * affected.
 */
@JacksonComponent
public class CambodiaLocalDateTimeSerializer extends StdSerializer<LocalDateTime> {

  private static final ZoneId CAMBODIA = ZoneId.of("Asia/Phnom_Penh");

  private final DateTimeFormatter formatter;

  public CambodiaLocalDateTimeSerializer() {
    this(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private CambodiaLocalDateTimeSerializer(DateTimeFormatter formatter) {
    super(LocalDateTime.class);
    this.formatter = formatter;
  }

  @Override
  public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
    gen.writeString(formatter.format(toCambodia(value)));
  }

  static LocalDateTime toCambodia(LocalDateTime value) {
    return value.atZone(ZoneId.systemDefault()).withZoneSameInstant(CAMBODIA).toLocalDateTime();
  }

  @Override
  public ValueSerializer<?> createContextual(SerializationContext ctxt, BeanProperty property) {
    JsonFormat.Value format = findFormatOverrides(ctxt, property, handledType());
    if (format == null || !format.hasPattern()) {
      return this;
    }
    Locale locale = format.hasLocale() ? format.getLocale() : Locale.ENGLISH;
    return new CambodiaLocalDateTimeSerializer(
        DateTimeFormatter.ofPattern(format.getPattern(), locale));
  }
}
