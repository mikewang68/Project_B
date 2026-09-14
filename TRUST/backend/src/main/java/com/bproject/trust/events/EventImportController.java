package com.bproject.trust.events;

import com.bproject.trust.identity.CurrentIdentity;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class EventImportController {
  private final CurrentIdentity identity;
  private final EventImportService importer;

  public EventImportController(CurrentIdentity identity, EventImportService importer) {
    this.identity = identity;
    this.importer = importer;
  }

  @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
  public Map<String, Object> importFile(Authentication a, @RequestPart MultipartFile file)
      throws Exception {
    return importer.importFile(file, identity.org(a), a.getName());
  }
}
