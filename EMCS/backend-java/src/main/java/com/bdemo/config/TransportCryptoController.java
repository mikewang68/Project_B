package com.bdemo.config;

import com.bdemo.common.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transport/crypto")
public class TransportCryptoController {
    @GetMapping("/frontend-config")
    public AjaxResult frontendConfig() {
        return AjaxResult.success(Map.ofEntries(
                Map.entry("transportCryptoEnabled", false),
                Map.entry("transportCryptoMode", "off"),
                Map.entry("transportCryptoActive", false),
                Map.entry("envelopeVersion", "1"),
                Map.entry("publicKeyUrl", "/transport/crypto/public-key"),
                Map.entry("enabledPaths", List.of()),
                Map.entry("requiredPaths", List.of()),
                Map.entry("excludePaths", List.of("/login", "/captchaImage", "/transport/crypto/frontend-config")),
                Map.entry("maxEncryptedGetUrlLength", 4096),
                Map.entry("configExpireAt", Instant.now().plusSeconds(3600).getEpochSecond())
        ));
    }

    @GetMapping("/public-key")
    public AjaxResult publicKey() {
        return AjaxResult.error(404, "传输加密未启用");
    }
}
