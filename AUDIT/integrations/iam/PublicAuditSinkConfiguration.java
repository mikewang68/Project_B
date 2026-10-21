package com.bdemo.iam.log;

import com.bproject.audit.client.LegacyLogMapper;
import com.bproject.audit.client.CanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

/** Copy into IAM log package after maintainer review. Existing SysAuditExport performs durable retries. */
@Configuration @ConditionalOnProperty(name="iam.audit.public.enabled",havingValue="true")
public class PublicAuditSinkConfiguration {
    @Bean SysAuditSink publicAuditSink(ObjectMapper mapper,
            @Value("${iam.audit.public.endpoint}") String endpoint,@Value("${iam.audit.public.token-file}") String tokenFile,
            @Value("${iam.audit.public.domain}") String domain,@Value("${iam.audit.public.zone}") String zone,
            @Value("${iam.audit.public.source-timezone:Asia/Shanghai}") String timezone) throws Exception {
        String token=Files.readString(Path.of(tokenFile)).trim();URI uri=URI.create(endpoint);
        if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null||token.length()<32)throw new IllegalArgumentException("Invalid public audit adapter");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (auditId,row)->{
            var event=LegacyLogMapper.map(row,"iam",domain,zone,ZoneId.of(timezone));
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(CanonicalJson.encode(mapper,event))).build(),HttpResponse.BodyHandlers.ofString());
            var body=mapper.readTree(response.body());
            if(response.statusCode()<200||response.statusCode()>=300||!body.path("stored").asBoolean()||!body.path("eventId").asText().equals(event.eventId())
                    ||!body.path("payloadHash").asText().equals(CanonicalJson.hash(CanonicalJson.encode(mapper,event))))throw new IllegalStateException("Public audit did not durably accept event");
            return auditId;
        };
    }
}
