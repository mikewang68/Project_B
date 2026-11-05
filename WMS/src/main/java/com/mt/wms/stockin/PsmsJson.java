package com.mt.wms.stockin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class PsmsJson {
    final ObjectMapper mapper;
    PsmsJson(ObjectMapper mapper){this.mapper=mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("联动数据序列化失败",e);}}
    <T>T read(String value,Class<T> type){try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException("联动数据读取失败",e);}}
    String hash(Object value){return hashText(write(value));}
    static String hashText(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
