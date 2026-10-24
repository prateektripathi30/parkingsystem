package com.ridewise.parking.exception;

public final class NoAvailableSpotException extends ParkingException {
    public NoAvailableSpotException(String message) {
        super(message);
    }
}
