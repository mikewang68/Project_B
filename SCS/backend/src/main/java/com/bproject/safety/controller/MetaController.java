package com.bproject.safety.controller;

import com.bproject.safety.support.masterdata.DemoMasterData;
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
 *
 * <p>字典数据统一来自 {@link DemoMasterData}（Demo 主数据唯一权威来源）：
 * areas / teams 为 {@code {code,name}} 结构，assignees 为
 * {@code {id,name,teamCode,teamName,demoUnverified}} 结构。</p>
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Meta", description = "基础字典")
public class MetaController {
    private final DemoMasterData masterData;

    public MetaController(DemoMasterData masterData) {
        this.masterData = masterData;
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
            result.put("areas", masterData.areas());
        }
        if (wanted.contains("teams")) {
            result.put("teams", masterData.teams());
        }
        if (wanted.contains("assignees")) {
            result.put("assignees", masterData.users());
        }
        return result;
    }
}
