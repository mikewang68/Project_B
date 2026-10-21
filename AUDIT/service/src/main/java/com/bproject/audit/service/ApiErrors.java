package com.bproject.audit.service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
@RestControllerAdvice
public class ApiErrors {
    private final LogStore store;
    public ApiErrors(LogStore store){this.store=store;}
    private void record(jakarta.servlet.http.HttpServletRequest request,String outcome){
        var client=(ServiceIdentity)request.getAttribute(ServiceAuthFilter.IDENTITY);
        if(client!=null)store.access(client,"EXECUTE",outcome,request.getRequestURI());
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> badInput(Exception e,jakarta.servlet.http.HttpServletRequest request) { record(request,"FAILED");return ResponseEntity.badRequest().body(Map.of("code", "INVALID_EVENT_OR_QUERY")); }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e,jakarta.servlet.http.HttpServletRequest request) { record(request,e.getStatusCode().value()==403?"DENIED":"FAILED");return ResponseEntity.status(e.getStatusCode()).body(Map.of("code", e.getReason() == null ? "REQUEST_REJECTED" : e.getReason())); }
}
