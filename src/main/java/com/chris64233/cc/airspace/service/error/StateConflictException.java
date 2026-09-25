package com.chris64233.cc.airspace.service.error;

public class StateConflictException extends ApiException {

    public StateConflictException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.RESOURCE_CONFLICT;
    }
}
