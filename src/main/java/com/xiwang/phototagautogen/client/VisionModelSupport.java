package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiwang.phototagautogen.domain.GeneratedTag;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class VisionModelSupport {
    static final String SYSTEM_PROMPT = "你是一名严谨的中文图片标注助手。只依据图片中可以观察到的内容，不猜测人物身份、精确地点或敏感属性。";
    private static final String PORTRAIT_ROOT = "人像";
    private static final String REPAIR_INSTRUCTION = "\n上一轮返回格式不合法。请重新输出，必须严格符合 JSON Schema，只能输出 JSON，不要 Markdown 代码块或额外解释。";

    private final ObjectMapper objectMapper;

    VisionModelSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String buildPrompt(Taxonomy taxonomy) {
        String requiredBranches = String.join("、", taxonomy.requiredBranches(PORTRAIT_ROOT));
        String recommendedBranches = String.join("、", taxonomy.recommendedBranches(PORTRAIT_ROOT));
        return "请分析这张图片并返回 JSON。\n"
                + "description：用简体中文写一段 60-120 个汉字的客观描述。\n"
                + "portraitSubject：当画面主体是清晰可见的人时返回 true，否则返回 false。多人照片只以最主要或最清晰的人物为准。\n"
                + "tags：返回不超过 15 个多级标签；只允许从下面词表中选择完整路径，证据不足时可以减少非必选标签，不得臆造。\n"
                + "每个标签 path 必须是 JSON Schema 枚举中的完整路径字符串，例如‘季节/春’或‘场景/自然/山地’，confidence 为 0 到 1 的数值。\n"
                + "portraitSubject=true 时，必须返回‘人像’分类，并保证以下必选分类各至少一个标签：" + requiredBranches + "。\n"
                + "人像建议分类为：" + recommendedBranches + "；仅在内容清晰可见时选择。\n"
                + "必选分类没有合适枚举或无法可靠判断时选择该分类的‘其它’；确认没有明显配饰时选择‘人像/配饰/无配饰’。\n"
                + "人像标签只描述主体人物；不要把不同人物的姿态、服饰、颜色或配饰混合。portraitSubject=false 时禁止返回任何‘人像’标签。\n"
                + "禁止输出人物姓名、精确地址、民族、宗教、健康状况等敏感或不可确认信息。\n"
                + "受控词表：\n" + taxonomy.promptText();
    }

    String repairPrompt(String prompt) {
        return prompt + REPAIR_INSTRUCTION;
    }

    ObjectNode schema(Taxonomy taxonomy) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description").put("type", "string");
        properties.putObject("portraitSubject").put("type", "boolean");
        ObjectNode tags = properties.putObject("tags");
        tags.put("type", "array").put("maxItems", 15);
        ObjectNode tagItem = tags.putObject("items");
        tagItem.put("type", "object");
        ObjectNode tagProperties = tagItem.putObject("properties");
        ObjectNode path = tagProperties.putObject("path");
        path.put("type", "string");
        ArrayNode allowedPaths = path.putArray("enum");
        taxonomy.allowedPathStrings().forEach(allowedPaths::add);
        tagProperties.putObject("confidence")
                .put("type", "number")
                .put("minimum", 0)
                .put("maximum", 1);
        tagItem.putArray("required").add("path").add("confidence");
        tagItem.put("additionalProperties", false);
        schema.putArray("required").add("description").add("portraitSubject").add("tags");
        schema.put("additionalProperties", false);
        return schema;
    }

    ImageAnalysis parseAnalysis(String content, String providerName) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(providerName + " 返回内容为空");
        }
        try {
            JsonNode result = objectMapper.readTree(content);
            String description = result.path("description").asText(null);
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("模型未返回有效描述");
            }
            JsonNode portraitSubjectNode = result.get("portraitSubject");
            if (portraitSubjectNode == null || !portraitSubjectNode.isBoolean()) {
                throw new IllegalArgumentException("模型未返回有效的 portraitSubject");
            }
            List<GeneratedTag> tags = new ArrayList<>();
            for (JsonNode tag : result.path("tags")) {
                JsonNode pathNode = tag.path("path");
                List<String> path;
                if (pathNode.isTextual()) {
                    path = List.of(pathNode.asText().split("/"));
                } else {
                    path = new ArrayList<>();
                    pathNode.forEach(segment -> path.add(segment.asText()));
                }
                BigDecimal confidence = tag.path("confidence").decimalValue();
                tags.add(new GeneratedTag(path, confidence));
            }
            return new ImageAnalysis(description.trim(), tags, portraitSubjectNode.asBoolean());
        } catch (Exception e) {
            throw new IllegalArgumentException(providerName + " 返回的 JSON 不合法", e);
        }
    }
}
