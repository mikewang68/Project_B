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
                validateConfigValue(item, newValue);
                mapper.updateValue(e.getKey(), newValue, now);
                changed++;
            }
        }
        return changed;
    }

    /**
     * 按配置项类型校验提交值：拒绝空值、非数字、非 true/false、枚举外取值与超长文本，
     * 防止绕过前端控件直接调用接口写入非法配置。
     */
    private void validateConfigValue(SysConfigItem item, String value) {
        String key = item.getKey();
        if (value == null || value.isBlank()) {
            throw BizException.badRequest("配置项不能为空：" + key);
        }
        String type = item.getType() == null ? "string" : item.getType();
        switch (type) {
            case "number" -> {
                try {
                    double n = Double.parseDouble(value.trim());
                    if (Double.isNaN(n) || Double.isInfinite(n)) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    throw BizException.badRequest("配置项「" + item.getLabel() + "」必须是数字");
                }
            }
            case "boolean" -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw BizException.badRequest("配置项「" + item.getLabel() + "」必须是 true 或 false");
                }
            }
            case "select" -> {
                List<String> options = parseOptions(item.getOptionsJson());
                if (options != null && !options.isEmpty() && !options.contains(value)) {
                    throw BizException.badRequest("配置项「" + item.getLabel() + "」取值不在允许范围内");
                }
            }
            default -> {
                if (value.length() > 500) {
                    throw BizException.badRequest("配置项内容过长（最多 500 字符）：" + key);
                }
            }
        }
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
