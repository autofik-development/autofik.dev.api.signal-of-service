package com.autofik.sos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autofik.sos.middleware.AuthMiddleware;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.ServletWebRequest;

class IdentityBranchesTest {

  private static UserContext.User user(String uuid) {
    return new UserContext.User(uuid, "USER", null, true, true, "active", false, null, null, null);
  }

  private static MechanicContext.Mechanic mechanic(String uuid) {
    return new MechanicContext.Mechanic(
        uuid, "MECHANIC", null, true, true, "active", false, null, null, null, null, null);
  }

  private static AppHeaderContext ctx(UserContext.User u, MechanicContext.Mechanic m) {
    return new AppHeaderContext(
        null,
        u == null ? null : new UserContext(u),
        m == null ? null : new MechanicContext(m, null),
        null,
        null,
        null);
  }

  private final String a = UUID.randomUUID().toString();

  @Test
  void aBlockThatNamesNobody_losesToTheOneThatDoes() {
    assertThat(ctx(user(a), mechanic(" ")).identity()).isInstanceOf(UserContext.User.class);
    assertThat(ctx(user(null), mechanic(a)).identity())
        .isInstanceOf(MechanicContext.Mechanic.class);
    assertThat(ctx(user(" "), mechanic(null)).identity())
        .as("neither names anybody")
        .isInstanceOf(UserContext.User.class);
    assertThat(ctx(null, mechanic(a)).identity()).isInstanceOf(MechanicContext.Mechanic.class);
    assertThat(ctx(user(a), null).identity()).isInstanceOf(UserContext.User.class);
    assertThat(ctx(null, null).identity()).isNull();
  }

  @Test
  void twoBlocksNamingTheSamePerson_agree_butDifferentPeopleAreRefused() {
    assertThat(ctx(user(a.toUpperCase()), mechanic(a)).identity())
        .isInstanceOf(MechanicContext.Mechanic.class);
    assertThatThrownBy(() -> ctx(user(a), mechanic(UUID.randomUUID().toString())).identity())
        .isInstanceOf(UserContextRejectedException.class);
  }

  // ── argument resolvers ──

  private static MethodParameter paramOf(Class<?> holder, String method) throws Exception {
    return new MethodParameter(holder.getDeclaredMethod(method, CurrentUser.class), 0);
  }

  static class Holder {
    void mech(@AuthMechanic CurrentUser u) {}

    void usr(@AuthUser CurrentUser u) {}

    void plain(CurrentUser u) {}

    void wrongTypeMech(@AuthMechanic String u) {}

    void wrongTypeUsr(@AuthUser String u) {}
  }

  @Test
  void resolversOnlySupportTheirOwnAnnotation() throws Exception {
    var mech = new AuthMechanicArgumentResolver();
    var usr = new AuthUserArgumentResolver();

    assertThat(mech.supportsParameter(paramOf(Holder.class, "mech"))).isTrue();
    assertThat(mech.supportsParameter(paramOf(Holder.class, "usr"))).isFalse();
    assertThat(mech.supportsParameter(paramOf(Holder.class, "plain"))).isFalse();
    assertThat(usr.supportsParameter(paramOf(Holder.class, "usr"))).isTrue();
    assertThat(usr.supportsParameter(paramOf(Holder.class, "mech"))).isFalse();
  }

  @Test
  void mechanicResolver_distinguishesDriver_nobody_andMechanic() throws Exception {
    var resolver = new AuthMechanicArgumentResolver();
    MethodParameter p = paramOf(Holder.class, "mech");
    CurrentUser who = new CurrentUser(UUID.randomUUID(), CallerRole.MECHANIC, null, null);

    MockHttpServletRequest nobody = new MockHttpServletRequest();
    assertThat(resolver.resolveArgument(p, null, new ServletWebRequest(nobody), null)).isNull();

    MockHttpServletRequest driver = new MockHttpServletRequest();
    driver.setAttribute(AuthMiddleware.CURRENT_USER_ATTRIBUTE, who);
    assertThatThrownBy(() -> resolver.resolveArgument(p, null, new ServletWebRequest(driver), null))
        .isInstanceOf(AccessDeniedException.class);

    MockHttpServletRequest mech = new MockHttpServletRequest();
    mech.setAttribute(AuthMiddleware.CURRENT_MECHANIC_ATTRIBUTE, who);
    assertThat(resolver.resolveArgument(p, null, new ServletWebRequest(mech), null)).isSameAs(who);
  }

  @Test
  void userResolver_readsTheRequestAttribute() throws Exception {
    var resolver = new AuthUserArgumentResolver();
    MockHttpServletRequest req = new MockHttpServletRequest();
    CurrentUser who = new CurrentUser(UUID.randomUUID(), CallerRole.USER, null, null);
    req.setAttribute(AuthMiddleware.CURRENT_USER_ATTRIBUTE, who);

    assertThat(
            resolver.resolveArgument(
                paramOf(Holder.class, "usr"), null, new ServletWebRequest(req), null))
        .isSameAs(who);
  }

  @Test
  void anAnnotationOnTheWrongType_isNotSupported() throws Exception {
    var m = Holder.class.getDeclaredMethod("wrongTypeMech", String.class);
    var u = Holder.class.getDeclaredMethod("wrongTypeUsr", String.class);

    assertThat(new AuthMechanicArgumentResolver().supportsParameter(new MethodParameter(m, 0)))
        .isFalse();
    assertThat(new AuthUserArgumentResolver().supportsParameter(new MethodParameter(u, 0)))
        .isFalse();
  }
}
