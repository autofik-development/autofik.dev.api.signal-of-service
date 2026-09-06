package com.autofik.sos.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// Throwaway reproduction — delete after use. Feeds the exact real SSO profile response
// through the same unwrap logic as SsoClient, outside Spring, to get the real exception
// in a terminal under direct control instead of guessing at "Unable to reach SSO".
class SsoClientUnwrapDebugTest {

  private static final String REAL_PROFILE_RESPONSE =
      """
            {"status":"success","message":"Profile retrieved successfully","code":"PROFILE_GET_SUCCESS","data":{"user":{"id":"3bd0af92-9812-460b-a42b-d8099d42d6b5","phoneNumber":"+855100015476","referralCode":"USC-L45-9841","userStatus":"active","profile":{"id":"ce10c371-1477-4b1c-a67b-1d275d7eff21","userId":"3bd0af92-9812-460b-a42b-d8099d42d6b5","username":"HappyPathTester","profileImage":"https://fsgw.evauto24.com/autofik-user-profile/profiles/3bd0af92-9812-460b-a42b-d8099d42d6b5/1786609504456.png","gender":"male","dob":"1995-01-01","createdAt":"2026-08-13 15:20:22","updatedAt":"2026-08-13 15:25:04"}}}}
            """;

  @Test
  void reproduce() {
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      JsonNode root = objectMapper.readTree(REAL_PROFILE_RESPONSE);
      boolean descended;
      do {
        descended = false;
        for (String envelope : new String[] {"data", "user", "mechanic", "vehicle"}) {
          JsonNode nested = root.get(envelope);
          if (nested != null && nested.isObject()) {
            root = nested;
            descended = true;
            break;
          }
        }
      } while (descended);
      System.out.println("after descend loop: " + root);

      JsonNode profile = root.get("profile");
      if (profile != null && profile.isObject() && root.isObject()) {
        ObjectNode merged = ((ObjectNode) root).deepCopy();
        merged.setAll((ObjectNode) profile);
        root = merged;
      }
      System.out.println("after merge: " + root);

      com.autofik.sos.dto.external.SsoProfile result =
          objectMapper.treeToValue(root, com.autofik.sos.dto.external.SsoProfile.class);
      System.out.println("RESULT: " + result);
    } catch (Throwable t) {
      System.out.println("THREW: " + t.getClass().getName() + ": " + t.getMessage());
      t.printStackTrace();
    }
  }
}
