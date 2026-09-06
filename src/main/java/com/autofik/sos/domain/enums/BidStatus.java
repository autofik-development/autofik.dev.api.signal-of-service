package com.autofik.sos.domain.enums;

public enum BidStatus {
  PENDING, // mechanic submitted, waiting for user response
  ACCEPTED, // user accepted this bid
  REJECTED, // user rejected this bid
  CANCELLED // mechanic withdrew this bid while it was still PENDING
}
