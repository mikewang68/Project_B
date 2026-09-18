package com.bdemo.sys.dict.mapper;

import com.bdemo.sys.dict.domain.DictItem;
import com.bdemo.sys.dict.domain.DictType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DictMapper {

    // ---------------- 分类 ----------------

    @Select("""
            SELECT id, dict_code AS code, dict_name AS name, remark, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM sys_dict_type
            WHERE deleted = 0
            ORDER BY sort_order, created_at
            """)
    List<DictType> selectTypes();

    @Select("""
            SELECT id, dict_code AS code, dict_name AS name, remark, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM sys_dict_type
            WHERE id = #{id} AND deleted = 0
            """)
    DictType selectTypeById(@Param("id") String id);

    @Select("""
            SELECT id, dict_code AS code, dict_name AS name, remark, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM sys_dict_type
            WHERE dict_code = #{code} AND deleted = 0
            """)
    DictType selectTypeByCode(@Param("code") String code);

    @Select("SELECT COUNT(1) FROM sys_dict_type WHERE dict_code = #{code} AND deleted = 0")
    int countTypeByCode(@Param("code") String code);

    @Insert("""
            INSERT INTO sys_dict_type
              (id, dict_code, dict_name, remark, status, sort_order, created_at, updated_at, deleted)
            VALUES
              (#{id}, #{code}, #{name}, #{remark}, #{status},
               COALESCE((SELECT MAX(sort_order) + 1 FROM sys_dict_type t), 1),
               #{now}, #{now}, 0)
            """)
    int insertType(@Param("id") String id, @Param("code") String code, @Param("name") String name,
                   @Param("remark") String remark, @Param("status") String status,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dict_type
            SET dict_name = #{name}, remark = #{remark}, status = #{status}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateType(@Param("id") String id, @Param("name") String name, @Param("remark") String remark,
                   @Param("status") String status, @Param("now") LocalDateTime now);

    @Update("UPDATE sys_dict_type SET deleted = 1, updated_at = #{now} WHERE id = #{id}")
    int logicDeleteType(@Param("id") String id, @Param("now") LocalDateTime now);

    // ---------------- 字典项 ----------------

    @Select("""
            <script>
            SELECT id, type_code AS typeCode, item_label AS label, item_value AS value,
                   sort_order AS sort, status, tag_type AS tagType, remark,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM sys_dict_item
            WHERE deleted = 0
            <if test="typeCode != null"> AND type_code = #{typeCode} </if>
            ORDER BY type_code, sort_order, created_at
            </script>
            """)
    List<DictItem> selectItems(@Param("typeCode") String typeCode);

    @Select("""
            SELECT id, type_code AS typeCode, item_label AS label, item_value AS value,
                   sort_order AS sort, status, tag_type AS tagType, remark,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM sys_dict_item
            WHERE id = #{id} AND deleted = 0
            """)
    DictItem selectItemById(@Param("id") String id);

    @Select("""
            SELECT COUNT(1) FROM sys_dict_item
            WHERE type_code = #{typeCode} AND item_value = #{value} AND deleted = 0
              AND (CAST(#{excludeId} AS VARCHAR) IS NULL OR id <> #{excludeId})
            """)
    int countItemValue(@Param("typeCode") String typeCode, @Param("value") String value,
                       @Param("excludeId") String excludeId);

    @Insert("""
            INSERT INTO sys_dict_item
              (id, type_code, item_value, item_label, tag_type, sort_order, status, remark,
               created_at, updated_at, deleted)
            VALUES
              (#{id}, #{typeCode}, #{value}, #{label}, #{tagType}, #{sort}, #{status}, #{remark},
               #{now}, #{now}, 0)
            """)
    int insertItem(@Param("id") String id, @Param("typeCode") String typeCode,
                   @Param("value") String value, @Param("label") String label,
                   @Param("tagType") String tagType, @Param("sort") Integer sort,
                   @Param("status") String status, @Param("remark") String remark,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE sys_dict_item
            SET item_label = #{label}, tag_type = #{tagType}, sort_order = #{sort},
                status = #{status}, remark = #{remark}, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
            """)
    int updateItem(@Param("id") String id, @Param("label") String label, @Param("tagType") String tagType,
                   @Param("sort") Integer sort, @Param("status") String status,
                   @Param("remark") String remark, @Param("now") LocalDateTime now);

    @Update("UPDATE sys_dict_item SET deleted = 1, updated_at = #{now} WHERE id = #{id}")
    int logicDeleteItem(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE sys_dict_item SET deleted = 1, updated_at = #{now} WHERE type_code = #{typeCode}")
    int logicDeleteItemsByType(@Param("typeCode") String typeCode, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM sys_dict_item WHERE type_code = #{typeCode}")
    int hardDeleteItemsByType(@Param("typeCode") String typeCode);
}
