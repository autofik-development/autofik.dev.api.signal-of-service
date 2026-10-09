package com.autofik.sos.service.impl;

import com.autofik.sos.domain.entity.Mechanic;
import com.autofik.sos.domain.entity.MechanicRate;
import com.autofik.sos.domain.entity.MechanicSkill;
import com.autofik.sos.domain.enums.MechanicRole;
import com.autofik.sos.repository.MechanicRateRepository;
import com.autofik.sos.repository.MechanicRepository;
import com.autofik.sos.repository.MechanicSkillRepository;
import com.autofik.sos.security.MechanicContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps this service's copy of a mechanic in step with SSO, from the profile the gateway puts in
 * the {@code mechanic-context} header: creates the row the first time the mechanic is seen, and
 * updates it when SSO's values change.
 *
 * <p>Synced: name, phone, email, role, service radius, skill tags and rating. Never touched:
 * location, H3 cell, availability, rating count, address and base location — those are this
 * service's own.
 *
 * <p>The header is unsigned, so every value is bounded before it is stored. A value that fails a
 * bound is skipped with a warning, not stored and not a reason to refuse the request: this runs
 * before the controller, and a profile glitch must not stop a mechanic using the app.
 */
@Component
@Slf4j
public class MechanicProfileSync {

  private static final int MAX_NAME = 30;
  private static final int MAX_EMAIL = 30;
  private static final int MAX_PHONE = 20;
  private static final int MAX_TAGS = 30;
  private static final int MAX_TAG_LENGTH = 50;
  private static final int MAX_RADIUS_KM = 100;
  private static final BigDecimal MAX_RATING = new BigDecimal("5.00");

  private final MechanicRepository mechanicRepository;
  private final MechanicSkillRepository skillRepository;
  private final MechanicRateRepository rateRepository;
  private final TransactionTemplate tx;

  // What was last written per mechanic, so a request whose profile has not changed costs no query.
  // ponytail: per instance. Several replicas each do one idempotent write per profile change; a
  // shared cache only matters if that ever shows up in the database load.
  private final Cache<UUID, String> lastSynced =
      Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();

  public MechanicProfileSync(
      MechanicRepository mechanicRepository,
      MechanicSkillRepository skillRepository,
      MechanicRateRepository rateRepository,
      TransactionTemplate tx) {
    this.mechanicRepository = mechanicRepository;
    this.skillRepository = skillRepository;
    this.rateRepository = rateRepository;
    this.tx = tx;
  }

  /** Never throws. {@code mechanicId} is the caller the gateway headers resolved to. */
  public void sync(UUID mechanicId, MechanicContext.Mechanic ctx) {
    if (mechanicId == null
        || ctx == null
        || (ctx.skillService() == null && ctx.profile() == null)) {
      return;
    }
    try {
      Snapshot snapshot = Snapshot.of(ctx);
      String fingerprint = snapshot.fingerprint();
      if (fingerprint.equals(lastSynced.getIfPresent(mechanicId))) {
        return;
      }
      try {
        tx.executeWithoutResult(status -> apply(mechanicId, snapshot));
      } catch (DataIntegrityViolationException raced) {
        // Two first requests both found no row; the other one created it, so this is now an update.
        tx.executeWithoutResult(status -> apply(mechanicId, snapshot));
      }
      lastSynced.put(mechanicId, fingerprint);
    } catch (Exception e) {
      lastSynced.invalidate(mechanicId);
      log.warn("Could not sync mechanic {} from the gateway profile: {}", mechanicId, e.toString());
    }
  }

  private void apply(UUID mechanicId, Snapshot s) {
    Mechanic mechanic = mechanicRepository.findById(mechanicId).orElse(null);
    if (mechanic == null) {
      if (s.fullName == null || s.phone == null) {
        log.warn("Mechanic {} not created: the profile has no usable name or phone", mechanicId);
        return;
      }
      mechanic =
          mechanicRepository.save(
              Mechanic.builder()
                  .mechanicId(mechanicId)
                  .fullName(s.fullName)
                  .phoneNumber(s.phone)
                  .email(s.email)
                  .mechanicRole(s.role)
                  .serviceRadiusKm(s.radiusKm != null ? s.radiusKm : 10)
                  .build());
      log.info("Created mechanic {} from the gateway profile", mechanicId);
    } else {
      if (s.fullName != null) mechanic.setFullName(s.fullName);
      if (s.phone != null) mechanic.setPhoneNumber(s.phone);
      if (s.email != null) mechanic.setEmail(s.email);
      if (s.role != null) mechanic.setMechanicRole(s.role);
      if (s.radiusKm != null) mechanic.setServiceRadiusKm(s.radiusKm);
      mechanicRepository.save(mechanic);
    }
    syncSkills(mechanic, s.tags);
    syncRating(mechanic, s.avgRate);
  }

  // One skill row per mechanic: SSO sends a flat tag list, and dispatch only reads the tags.
  private void syncSkills(Mechanic mechanic, String[] tags) {
    if (tags == null) {
      return;
    }
    if (tags.length == 0) {
      log.warn("Mechanic {} has no skill tags in the profile; keeping the old ones", id(mechanic));
      return;
    }
    List<MechanicSkill> rows = skillRepository.findByMechanicMechanicId(mechanic.getMechanicId());
    if (rows.isEmpty()) {
      skillRepository.save(MechanicSkill.builder().mechanic(mechanic).skillTags(tags).build());
      return;
    }
    MechanicSkill first = rows.get(0);
    if (!Arrays.equals(first.getSkillTags(), tags)) {
      first.setSkillTags(tags);
      skillRepository.save(first);
    }
    if (rows.size() > 1) {
      skillRepository.deleteAll(rows.subList(1, rows.size()));
    }
  }

  // The count is left alone: the profile carries only the average.
  private void syncRating(Mechanic mechanic, BigDecimal avgRate) {
    if (avgRate == null) {
      return;
    }
    MechanicRate rate =
        rateRepository
            .findByMechanic(mechanic)
            .orElseGet(() -> MechanicRate.builder().mechanic(mechanic).build());
    if (rate.getRatingAvg() == null || rate.getRatingAvg().compareTo(avgRate) != 0) {
      rate.setRatingAvg(avgRate);
      rateRepository.save(rate);
    }
  }

  private static UUID id(Mechanic mechanic) {
    return mechanic.getMechanicId();
  }

  /** Shared with {@link UserProfileSync}: trimmed value, or null when blank or over {@code max}. */
  static String bounded(String field, String value, int max) {
    return Snapshot.bounded(field, value, max);
  }

  /** The header's profile after every bound has been applied; a failed bound leaves a null. */
  private static final class Snapshot {
    String fullName;
    String phone;
    String email;
    MechanicRole role;
    Integer radiusKm;
    String[] tags; // null = the header said nothing about tags
    BigDecimal avgRate;

    static Snapshot of(MechanicContext.Mechanic ctx) {
      Snapshot s = new Snapshot();
      if (ctx.profile() != null) {
        s.fullName = bounded("fullName", ctx.profile().fullName(), MAX_NAME);
        s.email = bounded("email", ctx.profile().email(), MAX_EMAIL);
      }
      s.phone = bounded("phoneNumber", ctx.phoneNumber(), MAX_PHONE);
      s.role = role(ctx.mechanicType());
      if (ctx.skillService() != null) {
        s.radiusKm = radius(ctx.skillService().serviceRadiusKm());
        s.tags = tags(ctx.skillService().skillTag());
      }
      s.avgRate = rating(ctx.avgRate());
      return s;
    }

    String fingerprint() {
      return String.join(
          "|",
          Objects.toString(fullName),
          Objects.toString(phone),
          Objects.toString(email),
          Objects.toString(role),
          Objects.toString(radiusKm),
          tags == null ? "null" : String.join(",", tags),
          Objects.toString(avgRate));
    }

    private static String bounded(String field, String value, int max) {
      if (value == null || value.isBlank()) {
        return null;
      }
      String trimmed = value.trim();
      if (trimmed.length() > max) {
        log.warn("Profile {} is longer than {} characters; not stored", field, max);
        return null;
      }
      return trimmed;
    }

    private static MechanicRole role(String type) {
      if (type == null || type.isBlank()) {
        return null;
      }
      String key = type.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      return switch (key) {
        case "freelance", "freeland" -> MechanicRole.FREELANCE;
        case "under_garage", "garage" -> MechanicRole.UNDER_GARAGE;
        default -> {
          log.warn("Unknown mechanicType '{}'; role not stored", key);
          yield null;
        }
      };
    }

    private static Integer radius(Integer km) {
      if (km == null) {
        return null;
      }
      if (km < 1 || km > MAX_RADIUS_KM) {
        log.warn("Service radius {} km is outside 1..{}; not stored", km, MAX_RADIUS_KM);
        return null;
      }
      return km;
    }

    private static String[] tags(List<String> raw) {
      if (raw == null) {
        return null;
      }
      List<String> usable =
          raw.stream()
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(t -> !t.isEmpty() && t.length() <= MAX_TAG_LENGTH)
              .toList();
      String[] normalized = MechanicServiceImpl.normalizeTags(usable);
      if (normalized.length > MAX_TAGS) {
        log.warn("Profile has {} skill tags; keeping the first {}", normalized.length, MAX_TAGS);
        return Arrays.copyOf(normalized, MAX_TAGS);
      }
      return normalized;
    }

    private static BigDecimal rating(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        BigDecimal value = new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        if (value.signum() < 0 || value.compareTo(MAX_RATING) > 0) {
          log.warn("Rating {} is outside 0..5; not stored", raw);
          return null;
        }
        return value;
      } catch (NumberFormatException e) {
        log.warn("Rating is not a number; not stored");
        return null;
      }
    }
  }
}
