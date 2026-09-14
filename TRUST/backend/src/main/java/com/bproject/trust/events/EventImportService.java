package com.bproject.trust.events;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.validation.Validator;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EventImportService {
  private final EventService events;
  private final Validator validator;

  public EventImportService(EventService events, Validator validator) {
    this.events = events;
    this.validator = validator;
  }

  public Map<String, Object> importFile(MultipartFile file, String org, String actor)
      throws Exception {
    if (file.getSize() > 1024 * 1024) throw new ApiError(413, "导入文件不能超过 1 MiB");
    List<EventInput> inputs = new ArrayList<>();
    String text = new String(file.getBytes(), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
    if (Objects.toString(file.getOriginalFilename(), "").endsWith(".json"))
      inputs =
          Json.MAPPER.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    else if (Objects.toString(file.getOriginalFilename(), "").endsWith(".csv")) {
      try (var csv =
          CSVFormat.DEFAULT
              .builder()
              .setHeader()
              .setSkipHeaderRecord(true)
              .get()
              .parse(new StringReader(text))) {
        for (CSVRecord r : csv) {
          var fields = new LinkedHashMap<String, Object>(r.toMap());
          for (String key :
              List.of("bundleIds", "relatedBatchIds", "relatedEventRefs", "evidenceIds")) {
            String v = Objects.toString(fields.get(key), "");
            fields.put(key, v.isBlank() ? List.of() : Arrays.asList(v.split("\\|")));
          }
          if (Objects.toString(fields.get("quantity"), "").isBlank()) fields.remove("quantity");
          String d = Objects.toString(fields.get("details"), "");
          fields.put("details", d.isBlank() ? Map.of() : Json.map(d));
          inputs.add(Json.MAPPER.convertValue(fields, EventInput.class));
          if (inputs.size() > 200) break;
        }
      }
    } else throw new ApiError(415, "请导入 UTF-8 JSON 数组或 CSV 文件");
    if (inputs.isEmpty() || inputs.size() > 200) throw new ApiError(400, "每次导入应包含 1 至 200 条事件");
    var result = new ArrayList<Map<String, Object>>();
    int index = 0;
    for (var input : inputs) {
      index++;
      try {
        if (input == null || !validator.validate(input).isEmpty())
          throw new ApiError(400, "字段格式不正确");
        var saved = events.submit(input, org, actor, null);
        result.add(Map.of("row", index, "ok", true, "id", saved.get("id")));
      } catch (ApiError e) {
        result.add(Map.of("row", index, "ok", false, "message", e.getMessage()));
      }
    }
    return Map.of(
        "results",
        result,
        "accepted",
        result.stream().filter(v -> Boolean.TRUE.equals(v.get("ok"))).count());
  }
}
