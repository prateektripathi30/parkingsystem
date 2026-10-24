package com.ridewise.parking.exception;

public final class AlreadyParkedException extends ParkingException {
    public AlreadyParkedException(String message) {
        super(message);
    }
}
