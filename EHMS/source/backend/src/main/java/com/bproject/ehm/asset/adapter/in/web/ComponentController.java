package com.bproject.ehm.asset.adapter.in.web;

import com.bproject.ehm.asset.application.ComponentApplicationService;
import com.bproject.ehm.asset.application.ComponentCommand;
import com.bproject.ehm.asset.application.ComponentView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ehm/v1")
public class ComponentController {
    private final ComponentApplicationService components;

    public ComponentController(ComponentApplicationService components) {
        this.components = components;
    }

    @GetMapping("/assets/{assetCode}/components")
    public PageResult<ComponentView> list(@PathVariable String assetCode,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size,
                                           @RequestParam(required = false) String keyword) {
        return components.list(assetCode, new PageQuery(page, size), keyword);
    }

    @GetMapping("/components/{code}")
    public ComponentView get(@PathVariable String code) {
        return components.get(code);
    }

    @PostMapping("/assets/{assetCode}/components")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentView create(@PathVariable String assetCode, @Valid @RequestBody ComponentRequest request) {
        return components.create(assetCode, request.toCommand());
    }

    @PutMapping("/components/{code}")
    public ComponentView update(@PathVariable String code, @Valid @RequestBody ComponentRequest request) {
        return components.update(code, request.toCommand());
    }

    @DeleteMapping("/components/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String code) {
        components.archive(code);
    }

    public record ComponentRequest(
            String code,
            String parentCode,
            @NotBlank(message = "部件名称不能为空") String name,
            String category,
            String manufacturer,
            String model,
            String serialNumber,
            String criticality,
            String position,
            String installedOn,
            String status
    ) {
        ComponentCommand toCommand() {
            return new ComponentCommand(code, parentCode, name, category, manufacturer, model, serialNumber,
                    criticality, position, installedOn, status);
        }
    }
}
