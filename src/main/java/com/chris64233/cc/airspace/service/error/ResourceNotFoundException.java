package com.chris64233.cc.airspace.service.error;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.RESOURCE_CONFLICT;
    }
}
