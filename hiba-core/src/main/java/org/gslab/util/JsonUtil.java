package org.gslab.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;

public class JsonUtil {
    /**
     * 取得通用 ObjectMapper（美化輸出，適合日誌和除錯）
     */
    public static ObjectMapper getObjectMapper() {
        return ObjectMapperHolder.INSTANCE;
    }

    /**
     * 取得用於簽章的 Canonical ObjectMapper（緊湊格式，確保序列化一致性）
     * 用於需要確保序列化結果完全一致的場景，如數位簽章
     */
    public static ObjectMapper getCanonicalMapper() {
        return CanonicalMapperHolder.INSTANCE;
    }

    public static <T> T fromJson(final String json, final Class<T> valueType) throws JsonProcessingException {
        return getCanonicalMapper().readValue(json, valueType);
    }

    public static <T> T fromJson(final byte[] json, final Class<T> valueType) throws IOException {
        return getCanonicalMapper().readValue(json, valueType);
    }

    public static byte[] toJson(final Object obj) throws JsonProcessingException {
        return getCanonicalMapper().writeValueAsBytes(obj);
    }

    public static String toJsonString(final Object obj) throws JsonProcessingException {
        return getCanonicalMapper().writeValueAsString(obj);
    }

    public static String toPrettyJsonString(final Object obj) throws JsonProcessingException {
        return getCanonicalMapper().writeValueAsString(obj);
    }

    private JsonUtil() {
        // 工具類別不允許被實例化
    }

    /**
     * 通用 ObjectMapper（美化輸出，適合除錯和日誌）
     */
    private static class ObjectMapperHolder {
        private static final ObjectMapper INSTANCE = createObjectMapper();

        private static ObjectMapper createObjectMapper() {
            ObjectMapper mapper = JsonMapper.builder()
                    // 屬性按字母順序排列（確保一致性）
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    // Map 鍵排序
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    // 美化輸出（適合日誌和除錯）
                    .configure(SerializationFeature.INDENT_OUTPUT, true)
                    // 忽略未知屬性（更寬容的反序列化）
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    // 處理空 Bean
                    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                    // 不將日期序列化為時間戳
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .build();

            mapper.findAndRegisterModules();

            return mapper;
        }
    }

    /**
     * 用於簽章的 Canonical ObjectMapper（緊湊格式，確保序列化一致性）
     */
    private static class CanonicalMapperHolder {
        private static final ObjectMapper INSTANCE = createCanonicalMapper();

        private static ObjectMapper createCanonicalMapper() {
            ObjectMapper mapper = JsonMapper.builder()
                    // 屬性按字母順序排列（確保一致性）
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    // Map 鍵排序
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    // 不美化輸出（緊湊格式，確保簽章一致性）
                    .configure(SerializationFeature.INDENT_OUTPUT, false)
                    // 忽略未知屬性
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    // 處理空 Bean
                    .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                    // 不將日期序列化為時間戳
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                    .build();

            // 限制最大字串長度（防止 DoS 攻擊）
            mapper.getFactory()
                    .setStreamReadConstraints(StreamReadConstraints.builder()
                            .maxStringLength(10_000_000) // 10MB
                            .maxNumberLength(1000)
                            .maxNestingDepth(1000)
                            .build());

            mapper.findAndRegisterModules();

            return mapper;
        }
    }
}
