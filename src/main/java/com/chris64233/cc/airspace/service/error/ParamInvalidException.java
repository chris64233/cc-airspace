package com.chris64233.cc.airspace.service.error;

public class ParamInvalidException extends ApiException {

    public ParamInvalidException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.PARAM_INVALID;
    }
}
