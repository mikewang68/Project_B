package com.bdemo.sys.dict;

import com.bdemo.sys.common.BizException;
import com.bdemo.sys.dict.domain.DictItem;
import com.bdemo.sys.dict.domain.DictType;
import com.bdemo.sys.dict.dto.DictItemRequest;
import com.bdemo.sys.dict.dto.DictItemUpdateRequest;
import com.bdemo.sys.dict.dto.DictTypeRequest;
import com.bdemo.sys.dict.dto.DictTypeUpdateRequest;
import com.bdemo.sys.dict.mapper.DictMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DictService {

    private final DictMapper mapper;

    public DictService(DictMapper mapper) {
        this.mapper = mapper;
    }

    // ---------------- 分类 ----------------

    public List<DictType> listTypes() {
        return mapper.selectTypes();
    }

    public DictType createType(DictTypeRequest req) {
        if (mapper.countTypeByCode(req.getCode()) > 0) {
            throw BizException.conflict("字典分类编码已存在：" + req.getCode());
        }
        LocalDateTime now = LocalDateTime.now();
        String id = "dt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mapper.insertType(id, req.getCode(), req.getName(), req.getRemark(),
                req.getStatus() == null ? "active" : req.getStatus(), now);
        return mapper.selectTypeById(id);
    }

    public DictType updateType(String id, DictTypeUpdateRequest req) {
        DictType type = mustGetType(id);
        mapper.updateType(id, req.getName(), req.getRemark(),
                req.getStatus() == null ? "active" : req.getStatus(), LocalDateTime.now());
        return mapper.selectTypeById(id);
    }

    /** 删除分类时同事务逻辑删除其下全部字典项。 */
    @Transactional
    public void deleteType(String id) {
        DictType type = mustGetType(id);
        LocalDateTime now = LocalDateTime.now();
        mapper.logicDeleteType(id, now);
        mapper.logicDeleteItemsByType(type.getCode(), now);
    }

    private DictType mustGetType(String id) {
        DictType type = mapper.selectTypeById(id);
        if (type == null) {
            throw BizException.notFound("字典分类不存在");
        }
        return type;
    }

    // ---------------- 字典项 ----------------

    public List<DictItem> listItems(String typeCode) {
        return mapper.selectItems(typeCode);
    }

    public DictItem createItem(DictItemRequest req) {
        if (mapper.selectTypeByCode(req.getTypeCode()) == null) {
            throw BizException.badRequest("所属字典分类不存在：" + req.getTypeCode());
        }
        if (mapper.countItemValue(req.getTypeCode(), req.getValue(), null) > 0) {
            throw BizException.conflict("该分类下字典项值已存在：" + req.getValue());
        }
        LocalDateTime now = LocalDateTime.now();
        String id = "di" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mapper.insertItem(id, req.getTypeCode(), req.getValue(), req.getLabel(),
                emptyToNull(req.getTagType()), req.getSort() == null ? 0 : req.getSort(),
                req.getStatus() == null ? "active" : req.getStatus(), req.getRemark(), now);
        return mapper.selectItemById(id);
    }

    public DictItem updateItem(String id, DictItemUpdateRequest req) {
        DictItem item = mustGetItem(id);
        mapper.updateItem(id, req.getLabel(), emptyToNull(req.getTagType()),
                req.getSort() == null ? 0 : req.getSort(),
                req.getStatus() == null ? "active" : req.getStatus(), req.getRemark(),
                LocalDateTime.now());
        return mapper.selectItemById(id);
    }

    public void deleteItem(String id) {
        mustGetItem(id);
        mapper.logicDeleteItem(id, LocalDateTime.now());
    }

    private DictItem mustGetItem(String id) {
        DictItem item = mapper.selectItemById(id);
        if (item == null) {
            throw BizException.notFound("字典项不存在");
        }
        return item;
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
