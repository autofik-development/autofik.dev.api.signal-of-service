package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reading the caller the identity filter installed, and what happens when there is none. */
class CallerTest {

  private final UUID id = UUID.randomUUID();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void authenticate(Object principal, Object credentials, Object details) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            principal, credentials, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    auth.setDetails(details);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Test
  void mustMatch_acceptsTheSameIdentity_andAnOmittedClaim() {
    Caller.mustMatch(id, id);
    Caller.mustMatch(id, null);
  }

  @Test
  void mustMatch_refusesAnotherIdentity_andAnAnonymousCaller() {
    assertThatThrownBy(() -> Caller.mustMatch(id, UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> Caller.mustMatch(null, id)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> Caller.mustMatch(null, null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void withNoAuthentication_thereIsNoCallerIdOrRole() {
    assertThat(Caller.current()).isEmpty();
    assertThat(Caller.id()).isEmpty();
    assertThatThrownBy(Caller::role).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(Caller::token).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void anInstalledCaller_isReadBack() {
    CurrentUser current = new CurrentUser(id, CallerRole.MECHANIC, "device", "SOS");
    authenticate(id, "Bearer abc", current);

    assertThat(Caller.current()).contains(current);
    assertThat(Caller.id()).contains(id);
    assertThat(Caller.role()).isEqualTo(CallerRole.MECHANIC);
    assertThat(Caller.token()).isEqualTo("Bearer abc");
    assertThat(current.isMechanic()).isTrue();
    assertThat(current.authority()).isEqualTo("ROLE_MECHANIC");
  }

  @Test
  void aPrincipalThatIsNotAUuid_andDetailsThatAreNotACaller_areIgnored() {
    authenticate("someone", null, "not a CurrentUser");

    assertThat(Caller.id()).isEmpty();
    assertThat(Caller.current()).isEmpty();
    assertThatThrownBy(Caller::token).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void ssoRoles_mapToCallerRoles_orToNothing() {
    assertThat(CallerRole.fromSsoRole(" garage ")).contains(CallerRole.MECHANIC);
    assertThat(CallerRole.fromSsoRole("Mechanic")).contains(CallerRole.MECHANIC);
    assertThat(CallerRole.fromSsoRole("CUSTOMER")).contains(CallerRole.USER);
    assertThat(CallerRole.fromSsoRole("custom")).contains(CallerRole.USER);
    assertThat(CallerRole.fromSsoRole("admin")).isEmpty();
    assertThat(CallerRole.fromSsoRole(null)).isEmpty();
  }
}
