package com.xiwang.phototagautogen.service;

import com.xiwang.phototagautogen.domain.TagPath;
import com.xiwang.phototagautogen.domain.Taxonomy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxonomyLoaderTest {
    @Test
    void 应加载人像优先词表和必选规则() {
        Taxonomy taxonomy = new TaxonomyLoader().load();

        assertThat(taxonomy.version()).isEqualTo(3);
        assertThat(taxonomy.categories().keySet()).containsExactly("人像", "场景", "天气", "季节", "主体", "颜色", "活动");
        assertThat(taxonomy.categories().get("人像")).hasSize(15);
        assertThat(taxonomy.requiredBranches("人像")).containsExactly(
                "人脸角度", "姿态", "景别", "服饰类型", "主体颜色", "配饰", "场景", "拍摄风格");
        assertThat(taxonomy.recommendedBranches("人像")).containsExactly(
                "发型", "环境元素", "光照", "时间/季节", "摄影类型", "构图方式", "画面风格");
        assertThat(taxonomy.isAllowed(path("人像", "人脸角度", "正脸"))).isTrue();
        assertThat(taxonomy.isAllowed(path("人像", "配饰", "无配饰"))).isTrue();
        assertThat(taxonomy.isAllowed(path("人像", "场景", "其它"))).isTrue();
        assertThat(taxonomy.isAllowed(path("人像", "时间", "季节", "春"))).isTrue();
        assertThat(taxonomy.isAllowed(path("人物", "人数", "单人"))).isFalse();
        assertThat(taxonomy.isAllowed(path("季节", "春"))).isTrue();
        assertThat(taxonomy.isAllowed(path("季节", "时节", "春"))).isFalse();
    }

    @Test
    void 应找出缺失的人像必选分类() {
        Taxonomy taxonomy = new TaxonomyLoader().load();

        List<String> missing = taxonomy.missingRequiredBranches("人像", List.of(
                path("人像", "人脸角度", "正脸"),
                path("人像", "姿态", "站立"),
                path("人像", "景别", "全景")));

        assertThat(missing).containsExactly("服饰类型", "主体颜色", "配饰", "场景", "拍摄风格");
    }

    @Test
    void 人像规则未覆盖全部二级分类时应拒绝加载() {
        assertThatThrownBy(() -> new Taxonomy(3,
                java.util.Map.of("人像", java.util.Map.of(
                        "人脸角度", List.of("正脸", "其它"),
                        "姿态", List.of("站立", "其它"))),
                java.util.Map.of("人像", new Taxonomy.Rule(
                        List.of("人脸角度"), List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则未完整覆盖二级分类");
    }

    private TagPath path(String... segments) {
        return new TagPath(List.of(segments));
    }
}
