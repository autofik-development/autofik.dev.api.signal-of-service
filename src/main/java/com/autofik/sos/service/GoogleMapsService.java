package com.autofik.sos.service;

import com.autofik.sos.dto.external.DistanceInfo;
import org.locationtech.jts.geom.Point;

public interface GoogleMapsService {
    String reverseGeocode(double latitude, double longitude);
    DistanceInfo getDistanceInfo(Point origin, Point destination);
}
