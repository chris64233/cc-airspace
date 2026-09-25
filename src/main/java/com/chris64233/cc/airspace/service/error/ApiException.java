package com.chris64233.cc.airspace.service.error;

public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    public abstract ErrorType getErrorType();
}
