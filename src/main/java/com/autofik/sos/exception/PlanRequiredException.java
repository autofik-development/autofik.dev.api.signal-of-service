package com.autofik.sos.exception;

/** The caller's billing plan does not allow this request. Mapped to 403 with the reason. */
public class PlanRequiredException extends RuntimeException {

  public enum Reason {
    NO_PLAN("No billing plan was found for this account"),
    EXPIRED("Your plan has expired"),
    TIER_NOT_ALLOWED("Your plan does not include roadside help"),
    NO_TOKENS("No tokens left"),
    NOT_ENTITLED("Your plan cannot use this service");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final Reason reason;

  public PlanRequiredException(Reason reason) {
    super(reason.message());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
