package com.bproject.safety.controller;

import com.bproject.safety.support.dict.DictionarySeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础字典：区域 / 班组 / 责任人。支持 ?keys=areas,teams 过滤，缺省返回全部。
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta", description = "基础字典")
public class MetaController {
    private final DictionarySeed seed;

    public MetaController(DictionarySeed seed) {
        this.seed = seed;
    }

    @Operation(summary = "基础字典（区域/班组/责任人）",
            description = "可选参数 keys=areas,teams,assignees，缺省返回全部")
    @GetMapping("/dictionaries")
    public Map<String, Object> dictionaries(
            @Parameter(description = "逗号分隔的字典键，如 areas,teams")
            @RequestParam(name = "keys", required = false) String keys) {

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> wanted = keys == null || keys.isBlank()
                ? List.of("areas", "teams", "assignees")
                : List.of(keys.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (wanted.contains("areas")) {
            result.put("areas", seed.areas());
        }
        if (wanted.contains("teams")) {
            result.put("teams", seed.teams());
        }
        if (wanted.contains("assignees")) {
            result.put("assignees", seed.assignees());
        }
        return result;
    }
}
