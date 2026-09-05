package com.bdemo.energy.report;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/reports")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service){this.service=service;}
    @GetMapping("/templates") public AjaxResult templates(){return AjaxResult.success(service.templates()).add("msg","report templates");}
    // REQ-030/059/062: preview, export and archive consume the same canonical assembler.
    @PostMapping("/preview") public AjaxResult preview(@RequestBody Map<String,Object>body){return AjaxResult.success(service.preview(body)).add("msg","report preview");}
    @PostMapping("/export") public ResponseEntity<byte[]> export(@RequestBody Map<String,Object>body){return file(service.export(body));}
    @PostMapping("/archives") public AjaxResult archive(@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.archive(body,user.userName())).add("msg","report archived");}
    @GetMapping("/archives") public AjaxResult archives(){return AjaxResult.success(service.archives()).add("msg","report archives");}
    @GetMapping("/archives/{id}") public AjaxResult archive(@PathVariable long id){return AjaxResult.success(service.archive(id)).add("msg","report archive");}
    @GetMapping("/archives/{id}/export") public ResponseEntity<byte[]> exportArchive(@PathVariable long id){return file(service.exportArchive(id));}
    private ResponseEntity<byte[]> file(ReportService.ReportFile file){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"").header("X-Report-Signature",file.signature()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(file.content());}
}
