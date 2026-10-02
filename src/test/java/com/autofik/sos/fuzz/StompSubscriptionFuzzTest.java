package com.autofik.sos.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.autofik.sos.config.StompSubscriptionInterceptor;
import com.autofik.sos.service.SosService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.AntPathMatcher;

/**
 * The per-SOS topics are broadcast destinations and the simple broker matches a SUBSCRIBE
 * destination as an Ant pattern. So the question is not "does the destination look like the SOS
 * topic" but "would the broker deliver that SOS's frames to it" — those must be refused for anyone
 * who may not view the SOS, however the destination is spelled.
 */
class StompSubscriptionFuzzTest {

  private static final UUID VICTIM_SOS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final List<String> VICTIM_TOPICS =
      List.of("/topic/sos/" + VICTIM_SOS + "/location", "/topic/sos/" + VICTIM_SOS + "/media");

  private final AntPathMatcher brokerMatcher = new AntPathMatcher(); // what the broker uses
  private final StompSubscriptionInterceptor interceptor;
  private final Principal outsider = UUID.randomUUID()::toString;

  @SuppressWarnings("unchecked")
  StompSubscriptionFuzzTest() {
    SosService sosService = mock(SosService.class);
    // The caller is never a participant of any SOS.
    when(sosService.canView(any(), any())).thenReturn(false);
    ObjectProvider<SosService> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(sosService);
    interceptor = new StompSubscriptionInterceptor(provider);
  }

  private Message<byte[]> subscribe(String destination, Principal caller) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(caller);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  /** Whatever the destination, the only permitted failure is a clean access denial. */
  @Property(seed = Fuzz.SEED)
  void anyDestination_neverThrowsAnythingButAccessDenied(@ForAll("destinations") String dest) {
    try {
      interceptor.preSend(subscribe(dest, outsider), null);
    } catch (AccessDeniedException denied) {
      // fine
    }
  }

  /** The security property: an outsider is never allowed to match a SOS topic they cannot view. */
  @Property(seed = Fuzz.SEED)
  void outsider_cannotSubscribeToAnythingThatMatchesASosTopic(@ForAll("destinations") String dest) {
    boolean wouldReceiveVictimFrames =
        dest != null && VICTIM_TOPICS.stream().anyMatch(topic -> brokerMatcher.match(dest, topic));
    if (!wouldReceiveVictimFrames) return;

    boolean denied;
    try {
      interceptor.preSend(subscribe(dest, outsider), null);
      denied = false;
    } catch (AccessDeniedException e) {
      denied = true;
    }
    assertThat(denied)
        .as("outsider was allowed to subscribe to %s, which receives SOS topic frames", dest)
        .isTrue();
  }

  @Provide
  Arbitrary<String> destinations() {
    String v = VICTIM_SOS.toString();
    Arbitrary<String> known =
        Arbitraries.of(
            "/topic/sos/" + v + "/location",
            "/topic/sos/" + v + "/media",
            "/topic/sos/**",
            "/topic/sos/*",
            "/topic/sos/*/location",
            "/topic/sos/*/**",
            "/topic/**",
            "/topic/*/*/location",
            "/topic/sos/" + v + "/**",
            "/topic/sos/" + v,
            "/topic/sos//location",
            "/topic/sos/" + v + "/location\n",
            "/topic/sos/" + v + "/loc\nation",
            "/topic/SOS/" + v + "/location",
            "/topic/sos/" + v.toUpperCase() + "/location",
            "/topic//sos/" + v + "/location",
            "/queue/sos-new-request",
            "");
    // Random strings built from the characters that matter to path matching.
    Arbitrary<String> pathish =
        Arbitraries.strings().withChars("/*?{}.-abcdef0123456789\n").ofMaxLength(50);
    Arbitrary<String> underTopic = pathish.map(s -> "/topic/" + s);
    Arbitrary<String> underSos = pathish.map(s -> "/topic/sos/" + s);
    return Arbitraries.oneOf(known, underTopic, underSos, Fuzz.nastyStrings());
  }
}
