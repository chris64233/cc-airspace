package com.chris64233.cc.airspace.service.error;

/**
 * 航线与生效中的临时空域关闭冲突：提交或改道后的航线在关闭时段内仍使用被关闭航段。
 */
public class ClosureConflictException extends ApiException {

    public ClosureConflictException(String message) {
        super(message);
    }

    @Override
    public ErrorType getErrorType() {
        return ErrorType.CLOSURE_CONFLICT;
    }
}
