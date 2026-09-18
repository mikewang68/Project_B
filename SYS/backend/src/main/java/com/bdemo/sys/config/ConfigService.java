package com.bdemo.sys.config;

import com.bdemo.sys.common.BizException;
import com.bdemo.sys.config.domain.SysConfigItem;
import com.bdemo.sys.config.dto.ConfigGroupRequest;
import com.bdemo.sys.config.mapper.ConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConfigService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STR = new TypeReference<>() {
    };

    private final ConfigMapper mapper;

    public ConfigService(ConfigMapper mapper) {
        this.mapper = mapper;
    }

    public List<SysConfigItem> list() {
        List<SysConfigItem> rows = mapper.selectAll();
        for (SysConfigItem c : rows) {
            c.setOptions(parseOptions(c.getOptionsJson()));
        }
        return rows;
    }

    /**
     * 按组保存：仅更新提交且值发生变化、属于该组且 editable=1 的键。
     *
     * @return 实际更新条数
     */
    @Transactional
    public int saveGroup(String group, ConfigGroupRequest request) {
        List<SysConfigItem> all = mapper.selectAll();
        Map<String, SysConfigItem> byKey = all.stream()
                .collect(Collectors.toMap(SysConfigItem::getKey, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        for (ConfigGroupRequest.Entry e : request.getEntries()) {
            SysConfigItem item = byKey.get(e.getKey());
            if (item == null) {
                throw BizException.badRequest("配置项不存在：" + e.getKey());
            }
            if (!group.equals(item.getGroup())) {
                throw BizException.badRequest("配置项不属于该分组：" + e.getKey());
            }
            if (item.getEditable() == null || item.getEditable() != 1) {
                throw BizException.forbidden("配置项只读，不可修改：" + e.getKey());
            }
            String newValue = e.getValue() == null ? "" : e.getValue();
            if (!newValue.equals(item.getValue())) {
                mapper.updateValue(e.getKey(), newValue, now);
                changed++;
            }
        }
        return changed;
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> v = JSON.readValue(json, LIST_STR);
            return v == null || v.isEmpty() ? null : new ArrayList<>(v);
        } catch (Exception e) {
            return null;
        }
    }
}
