package com.chris64233.cc.airspace.service.error;

public class CapacityExceededException extends ApiException {

    public CapacityExceededException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.CAPACITY_EXCEEDED;
    }
}
