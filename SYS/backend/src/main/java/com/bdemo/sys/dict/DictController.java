package com.bdemo.sys.dict;

import com.bdemo.sys.common.R;
import com.bdemo.sys.common.WebUtils;
import com.bdemo.sys.dict.domain.DictItem;
import com.bdemo.sys.dict.domain.DictType;
import com.bdemo.sys.dict.dto.DictItemRequest;
import com.bdemo.sys.dict.dto.DictItemUpdateRequest;
import com.bdemo.sys.dict.dto.DictTypeRequest;
import com.bdemo.sys.dict.dto.DictTypeUpdateRequest;
import com.bdemo.sys.log.LogService;
import com.bdemo.sys.security.JwtAuthFilter;
import com.bdemo.sys.security.LoginUser;
import com.bdemo.sys.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/dict")
public class DictController {

    private final DictService dictService;
    private final LogService logService;

    public DictController(DictService dictService, LogService logService) {
        this.dictService = dictService;
        this.logService = logService;
    }

    // ---------------- 分类 ----------------

    @GetMapping("/types")
    @RequirePerm("sys:dict:type:view")
    public R<List<DictType>> types() {
        return R.ok(dictService.listTypes());
    }

    @PostMapping("/types")
    @RequirePerm("sys:dict:type:add")
    public R<DictType> createType(@Valid @RequestBody DictTypeRequest req, HttpServletRequest http) {
        DictType type = dictService.createType(req);
        record(http, "add", req.getCode(), "新增字典分类「" + req.getName() + "」");
        return R.ok(type);
    }

    @PutMapping("/types/{id}")
    @RequirePerm("sys:dict:type:edit")
    public R<DictType> updateType(@PathVariable String id,
                                  @Valid @RequestBody DictTypeUpdateRequest req, HttpServletRequest http) {
        DictType type = dictService.updateType(id, req);
        record(http, "edit", type.getCode(), "编辑字典分类「" + type.getName() + "」");
        return R.ok(type);
    }

    @DeleteMapping("/types/{id}")
    @RequirePerm("sys:dict:type:delete")
    public R<Void> deleteType(@PathVariable String id, HttpServletRequest http) {
        DictType type = dictService.listTypes().stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
        dictService.deleteType(id);
        record(http, "delete", type == null ? id : type.getCode(),
                "删除字典分类「" + (type == null ? id : type.getName()) + "」及其字典项");
        return R.ok();
    }

    // ---------------- 字典项 ----------------

    @GetMapping("/items")
    @RequirePerm("sys:dict:item:view")
    public R<List<DictItem>> items(@RequestParam(required = false) String typeCode) {
        return R.ok(dictService.listItems(typeCode));
    }

    @PostMapping("/items")
    @RequirePerm("sys:dict:item:add")
    public R<DictItem> createItem(@Valid @RequestBody DictItemRequest req, HttpServletRequest http) {
        DictItem item = dictService.createItem(req);
        record(http, "add", req.getTypeCode() + "." + req.getValue(),
                "新增字典项「" + req.getLabel() + "」");
        return R.ok(item);
    }

    @PutMapping("/items/{id}")
    @RequirePerm("sys:dict:item:edit")
    public R<DictItem> updateItem(@PathVariable String id,
                                  @Valid @RequestBody DictItemUpdateRequest req, HttpServletRequest http) {
        DictItem item = dictService.updateItem(id, req);
        record(http, "edit", item.getTypeCode() + "." + item.getValue(),
                "编辑字典项「" + item.getLabel() + "」");
        return R.ok(item);
    }

    @DeleteMapping("/items/{id}")
    @RequirePerm("sys:dict:item:delete")
    public R<Void> deleteItem(@PathVariable String id, HttpServletRequest http) {
        DictItem item = dictService.listItems(null).stream().filter(i -> i.getId().equals(id)).findFirst().orElse(null);
        dictService.deleteItem(id);
        record(http, "delete",
                item == null ? id : item.getTypeCode() + "." + item.getValue(),
                "删除字典项「" + (item == null ? id : item.getLabel()) + "」");
        return R.ok();
    }

    private void record(HttpServletRequest http, String action, String target, String detail) {
        LoginUser me = JwtAuthFilter.currentUser();
        logService.record(me, "operation", "dict", action, target, detail,
                http.getMethod(), http.getRequestURI(), WebUtils.clientIp(http), "success");
    }
}
