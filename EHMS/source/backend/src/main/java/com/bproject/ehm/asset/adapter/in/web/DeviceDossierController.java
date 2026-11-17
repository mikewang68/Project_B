package com.bproject.ehm.asset.adapter.in.web;
import com.bproject.ehm.asset.application.DeviceDossierService;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController @Profile("server") @RequestMapping("/api/ehm/v1/devices/{code}/dossier")
public class DeviceDossierController {
    private final DeviceDossierService service;
    public DeviceDossierController(DeviceDossierService service){this.service=service;}
    @GetMapping public DeviceDossier get(@PathVariable String code){return service.get(code);}
    @PutMapping public DeviceDossier save(@PathVariable String code,@RequestBody DeviceDossier value){return service.update(code,value);}
    @GetMapping("/maintenance-due") public Map<String,Object> due(@PathVariable String code){return service.due(code);}
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDossier upload(@PathVariable String code,@RequestParam long version,@RequestParam String title,
                               @RequestParam String kind,@RequestParam String revision,@RequestPart MultipartFile file)throws java.io.IOException{
        return service.upload(code,version,title,kind,revision,file.getOriginalFilename(),file.getBytes());
    }
    @DeleteMapping("/documents/{id}") public DeviceDossier archive(@PathVariable String code,@PathVariable String id,@RequestParam long version){return service.archive(code,id,version);}
    @GetMapping("/documents/{id}/content") public ResponseEntity<byte[]> download(@PathVariable String code,@PathVariable String id){
        var value=service.download(code,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.metadata().contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("X-EHM-Document-Sha256",value.metadata().sha256())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.metadata().filename(),StandardCharsets.UTF_8).build().toString()).body(value.bytes());
    }
}
