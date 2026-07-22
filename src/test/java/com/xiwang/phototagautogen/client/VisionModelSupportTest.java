package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.domain.Taxonomy;
import com.xiwang.phototagautogen.service.TaxonomyLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisionModelSupportTest {
    private static final String EXAMPLE_MARKER =
            "人像 JSON 返回示例（仅展示格式，实际内容必须依据当前图片并从受控词表选择）：\n";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 提示词应固定一级分类并提供合法人像Json示例() throws Exception {
        Taxonomy taxonomy = new TaxonomyLoader().load();
        String prompt = new VisionModelSupport(objectMapper).buildPrompt(taxonomy);

        assertThat(prompt).contains(
                "一级分类只能从以下预设分类中选择：人像、风光",
                EXAMPLE_MARKER);

        int exampleStart = prompt.indexOf(EXAMPLE_MARKER) + EXAMPLE_MARKER.length();
        int exampleEnd = prompt.indexOf("\n受控词表：", exampleStart);
        JsonNode example = objectMapper.readTree(prompt.substring(exampleStart, exampleEnd));

        assertThat(example.path("portraitSubject").asBoolean()).isTrue();
        assertThat(example.path("description").asText()).isNotBlank();
        assertThat(example.path("tags"))
                .extracting(tag -> tag.path("path").asText())
                .contains(
                        "人像/人脸角度/正脸",
                        "人像/姿态/站立",
                        "人像/景别/中景",
                        "人像/服饰类型/休闲装",
                        "人像/主体颜色/蓝",
                        "人像/配饰/无配饰",
                        "人像/场景/街道",
                        "人像/拍摄风格/清新");
        assertThat(example.path("tags"))
                .allSatisfy(tag -> {
                    String path = tag.path("path").asText();
                    assertThat(tag.path("parentTag").asText())
                            .isEqualTo(path.substring(0, path.lastIndexOf('/')));
                });
        assertThat(prompt).contains("每个标签必须包含 parentTag");
    }
}
