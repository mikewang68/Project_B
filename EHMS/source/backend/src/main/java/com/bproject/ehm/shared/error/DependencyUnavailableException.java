package com.bproject.ehm.shared.error;

public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message, Throwable cause) { super(message, cause); }
    public DependencyUnavailableException(String message) { super(message); }
}
