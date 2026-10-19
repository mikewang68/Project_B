package com.bproject.trust.ingress;

import com.bproject.trust.shared.web.ApiError;
import java.util.Map;

public class IngressError extends ApiError {
  public final String code;

  public IngressError(int status, String code, String message) {
    super(status, message);
    this.code = code;
  }

  public Map<String, String> body() { return Map.of("code", code, "message", getMessage()); }
  public static IngressError invalid() {
    return new IngressError(400, "INVALID_REQUEST", "通用事件格式不正确");
  }
  public static IngressError missing() {
    return new IngressError(404, "RECEIPT_NOT_FOUND", "来源记录不存在");
  }
}
