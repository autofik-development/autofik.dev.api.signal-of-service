package com.autofik.sos.service.impl;

import com.autofik.sos.dto.external.DistanceInfo;
import com.autofik.sos.service.GoogleMapsService;
import com.google.maps.DistanceMatrixApi;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.model.DistanceMatrix;
import com.google.maps.model.DistanceMatrixElement;
import com.google.maps.model.DistanceMatrixElementStatus;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.google.maps.model.TravelMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleMapsServiceImpl implements GoogleMapsService {

    private final GeoApiContext geoApiContext;

    @Override
    public String reverseGeocode(double latitude, double longitude) {
        try {
            GeocodingResult[] results = GeocodingApi
                    .reverseGeocode(geoApiContext, new LatLng(latitude, longitude))
                    .await();
            return results.length > 0 ? results[0].formattedAddress : null;
        } catch (Exception e) {
            log.warn("Reverse geocode failed for ({}, {}): {}", latitude, longitude, e.getMessage());
            return null;
        }
    }

    @Override
    public DistanceInfo getDistanceInfo(Point origin, Point destination) {
        try {
            DistanceMatrix matrix = DistanceMatrixApi.newRequest(geoApiContext)
                    .origins(new LatLng(origin.getY(), origin.getX()))
                    .destinations(new LatLng(destination.getY(), destination.getX()))
                    .mode(TravelMode.DRIVING)
                    .await();
            DistanceMatrixElement element = matrix.rows[0].elements[0];
            if (element.status != DistanceMatrixElementStatus.OK) return null;
            return new DistanceInfo(
                    element.distance.inMeters,
                    element.duration.inSeconds,
                    element.distance.humanReadable,
                    element.duration.humanReadable
            );
        } catch (Exception e) {
            log.warn("Distance matrix failed (origin={}, destination={}): {}", origin, destination, e.getMessage());
            return null;
        }
    }
}
