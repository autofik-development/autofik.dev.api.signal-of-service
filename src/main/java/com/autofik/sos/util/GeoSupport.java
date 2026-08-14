package com.autofik.sos.util;

import com.uber.h3core.H3Core;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import java.io.IOException;
import java.util.List;

/**
 * The H3 grid and WGS-84 geometry settings shared by dispatch, availability search, and the
 * location writer.
 *
 * <p>These were duplicated across three classes with a comment asking the next reader to keep them
 * in sync by hand. They are one thing: the resolution decides what is stored in
 * {@code current_h3_index}, and the cell spacing below is derived from that resolution — changing
 * either one alone silently breaks every search.
 */
public final class GeoSupport {

    // Resolution 7 ≈ 5 km² per H3 cell — fixed by what is stored in current_h3_index on Mechanic.
    // Changing this would require a schema + data migration; kRing radius is the adaptive variable
    // instead.
    public static final int H3_RESOLUTION = 7;

    // Center-to-center distance between adjacent resolution-7 cells ≈ edge × √3 ≈ 2.114 km.
    // Used to convert a km radius into the minimum kRing radius that covers it.
    private static final double H3_R7_CENTER_TO_CENTER_KM = 2.114;

    // Standard SRID 4326 geometry factory for GPS tracking (WGS 84 latitude/longitude)
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private static final H3Core H3;

    static {
        try {
            H3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private GeoSupport() {
    }

    /** JTS orders coordinates x,y — longitude first. Getting this backwards puts Cambodia at sea. */
    public static Point point(double lat, double lng) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat));
    }

    public static String h3Address(double lat, double lng) {
        return H3.geoToH3Address(lat, lng, H3_RESOLUTION);
    }

    /** Every cell whose contents could fall within radiusKm of the point — the coarse Stage 1 set. */
    public static List<String> searchCells(double lat, double lng, int radiusKm) {
        return H3.kRing(h3Address(lat, lng), ringRadiusFor(radiusKm));
    }

    // Converts a km radius into the minimum kRing depth that fully covers it at resolution 7.
    // +1 ring of buffer accounts for partial hex overlap at the boundary.
    public static int ringRadiusFor(int radiusKm) {
        return (int) Math.ceil(radiusKm / H3_R7_CENTER_TO_CENTER_KM) + 1;
    }
}
