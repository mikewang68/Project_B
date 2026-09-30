package com.bproject.trust.shared.web;

public class ApiError extends RuntimeException {
  public final int status;

  public ApiError(int status, String message) {
    super(message);
    this.status = status;
  }
}
