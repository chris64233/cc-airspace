package com.chris64233.cc.airspace.service.error;

public class IdempotentConflictException extends ApiException {

    public IdempotentConflictException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.IDEMPOTENT_CONFLICT;
    }
}
