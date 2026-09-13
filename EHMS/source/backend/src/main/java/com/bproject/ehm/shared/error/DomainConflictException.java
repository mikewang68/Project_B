package com.bproject.ehm.shared.error;

public class DomainConflictException extends RuntimeException {
    public DomainConflictException(String message) {
        super(message);
    }
}
