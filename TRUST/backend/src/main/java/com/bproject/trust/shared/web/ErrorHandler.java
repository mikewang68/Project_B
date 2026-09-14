package com.bproject.trust.shared.web;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> api(ApiError e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "输入格式不正确，请检查必填字段、时间及文件引用"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large(Exception e) {
    return ResponseEntity.status(413).body(Map.of("message", "单文件不能超过 20 MiB"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(Exception e) {
    return ResponseEntity.status(503).body(Map.of("message", "记录未确认接收，数据库暂不可用或存在写入冲突，请保留原记录后重试"));
  }
}
