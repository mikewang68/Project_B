package com.bproject.trust.evidence;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.identity.CurrentIdentity;
import com.bproject.trust.shared.web.ApiError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class EvidenceController {
  private final CurrentIdentity identity;
  private final EvidenceService evidence;
  private final AuditService audit;

  public EvidenceController(
      CurrentIdentity identity, EvidenceService evidence, AuditService audit) {
    this.identity = identity;
    this.evidence = evidence;
    this.audit = audit;
  }

  @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
  public Map<String, Object> upload(Authentication a, @RequestPart MultipartFile file)
      throws Exception {
    var result = evidence.upload(file, identity.org(a), a.getName());
    audit.record(identity.org(a), a.getName(), "UPLOAD", (String) result.get("id"), null);
    return result;
  }

  @GetMapping("/evidence/{id}/download")
  public ResponseEntity<byte[]> download(Authentication a, @PathVariable String id)
      throws Exception {
    var ev = evidence.get(id, identity.org(a));
    if (ev.get("cid") == null) throw new ApiError(409, "证据尚未完成归档");
    byte[] bytes = evidence.storedBytes(ev);
    audit.record(identity.org(a), a.getName(), "DOWNLOAD", id, null);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename((String) ev.get("filename"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType((String) ev.get("mime")))
        .body(bytes);
  }
}
