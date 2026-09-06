package com.autofik.sos.dto.external;

public record DistanceInfo(
    long distanceMeters, long durationSeconds, String distanceText, String durationText) {}
