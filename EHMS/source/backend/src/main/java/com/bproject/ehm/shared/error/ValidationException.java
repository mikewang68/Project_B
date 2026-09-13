package com.bproject.ehm.shared.error;

public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
