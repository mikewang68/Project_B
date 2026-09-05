package com.bdemo.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** RuoYi-compatible response envelope used by the existing Vue client. */
public final class AjaxResult extends LinkedHashMap<String, Object> {
    private AjaxResult(int code, String msg) {
        put("code", code);
        put("msg", msg);
    }

    public static AjaxResult success() {
        return new AjaxResult(200, "操作成功");
    }

    public static AjaxResult success(Object data) {
        return success().data(data);
    }

    public static AjaxResult success(String msg, Object data) {
        return new AjaxResult(200, msg).data(data);
    }

    public static AjaxResult error(int code, String msg) {
        return new AjaxResult(code, msg);
    }

    public AjaxResult data(Object data) {
        put("data", data);
        return this;
    }

    public AjaxResult add(String key, Object value) {
        put(key, value);
        return this;
    }

    public AjaxResult addAll(Map<String, ?> values) {
        putAll(values);
        return this;
    }
}
