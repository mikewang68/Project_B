package com.mt.wms.integration;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/v1/integration")
class SpreadsheetController {
    private final SpreadsheetService service;
    SpreadsheetController(SpreadsheetService service){this.service=service;}
    @GetMapping("/tasks") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<List<IntegrationModels.TaskView>> tasks(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.list(p(a),s),id(r));}
    @PostMapping("/tasks/export/{resource}") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<IntegrationModels.TaskView> export(@PathVariable String resource,Authentication a,HttpSession s,HttpServletRequest r)throws Exception{return ApiResponse.ok(service.export(resource,p(a),s),id(r));}
    @PostMapping(value="/tasks/import/{resource}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<IntegrationModels.TaskView> upload(@PathVariable String resource,@RequestPart("file")MultipartFile file,Authentication a,HttpSession s,HttpServletRequest r)throws Exception{return ApiResponse.ok(service.upload(resource,file,p(a),s),id(r));}
    @GetMapping("/tasks/{taskId}/download") @PreAuthorize("hasAuthority('integration:manage')") ResponseEntity<FileSystemResource> download(@PathVariable long taskId,Authentication a,HttpSession s){Path path=service.download(taskId,p(a),s);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(service.downloadName(taskId,p(a),s),StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(path));}
    @GetMapping("/templates/{resource}") @PreAuthorize("hasAuthority('integration:manage')") void template(@PathVariable String resource,HttpServletResponse response)throws Exception{response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(resource.toUpperCase()+"-template.xlsx",StandardCharsets.UTF_8).build().toString());service.template(resource,response.getOutputStream());}
    private static WmsPrincipal p(Authentication a){return (WmsPrincipal)a.getPrincipal();}
    private static String id(HttpServletRequest r){return (String)r.getAttribute(RequestIdFilter.ATTRIBUTE);}
}
