package com.petever.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

final class VeterinaryClinicCoordinateConverter {
    record Point(Double latitude, Double longitude) {}

    private static final String KOREAN_1985_CENTRAL =
            "+proj=tmerc +lat_0=38 +lon_0=127.0028902777778 +k=1 +x_0=200000 +y_0=500000 "
            + "+ellps=bessel +towgs84=-145.907,505.034,685.756,-1.162,2.347,1.592,6.342 +units=m +no_defs";
    private final CoordinateTransform transform;

    VeterinaryClinicCoordinateConverter() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem source = factory.createFromParameters("EPSG:5174", KOREAN_1985_CENTRAL);
        CoordinateReferenceSystem target = factory.createFromParameters("WGS84",
                "+proj=longlat +datum=WGS84 +no_defs");
        transform = new CoordinateTransformFactory().createTransform(source, target);
    }

    Point convert(String xValue, String yValue) {
        if (xValue.isBlank() || yValue.isBlank()) return new Point(null, null);
        try {
            ProjCoordinate target = new ProjCoordinate();
            transform.transform(new ProjCoordinate(Double.parseDouble(xValue), Double.parseDouble(yValue)), target);
            if (!Double.isFinite(target.x) || !Double.isFinite(target.y)
                    || target.y < 30 || target.y > 39 || target.x < 120 || target.x > 135)
                throw new IllegalArgumentException("Coordinate outside supported area");
            return new Point(round(target.y), round(target.x));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid source coordinate", ex);
        }
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP).doubleValue();
    }
}
