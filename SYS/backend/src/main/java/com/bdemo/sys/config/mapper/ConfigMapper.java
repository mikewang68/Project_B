package com.bdemo.sys.config.mapper;

import com.bdemo.sys.config.domain.SysConfigItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConfigMapper {

    String COLS = """
            id, config_group AS "group", config_name AS label, config_key AS "key",
            config_value AS value, config_type AS type, options_json AS optionsJson,
            unit, remark, sort_order AS sortOrder, editable, updated_at AS updatedAt
            """;

    @Select("SELECT " + COLS + " FROM sys_config ORDER BY sort_order, id")
    List<SysConfigItem> selectAll();

    @Select("SELECT " + COLS + " FROM sys_config WHERE config_key = #{key}")
    SysConfigItem selectByKey(@Param("key") String key);

    @Update("""
            UPDATE sys_config
            SET config_value = #{value}, updated_at = #{now}
            WHERE config_key = #{key} AND editable = 1
            """)
    int updateValue(@Param("key") String key, @Param("value") String value,
                    @Param("now") LocalDateTime now);
}
