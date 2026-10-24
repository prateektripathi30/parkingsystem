package com.ridewise.parking.model;

import java.util.List;

public enum VehicleType {
    MOTORCYCLE(List.of(SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.LARGE, SpotType.BUS)),
    CAR(List.of(SpotType.COMPACT, SpotType.LARGE)),
    BUS(List.of(SpotType.BUS));

    private final List<SpotType> compatibleSpotTypes;

    VehicleType(List<SpotType> compatibleSpotTypes) {
        this.compatibleSpotTypes = compatibleSpotTypes;
    }

    public List<SpotType> compatibleSpotTypes() {
        return compatibleSpotTypes;
    }
}
