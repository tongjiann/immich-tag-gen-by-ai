package com.xiwang.phototagautogen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xiwang.phototagautogen.domain.Taxonomy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaxonomyLoader {
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Taxonomy load() {
        try {
            JsonNode root = yamlMapper.readTree(new ClassPathResource("taxonomy.yml").getInputStream());
            int version = root.path("version").asInt(1);
            Map<String, Map<String, List<String>>> categories = parseCategories(root.path("categories"));
            if (categories.isEmpty()) {
                throw new IllegalStateException("taxonomy.yml 未配置任何分类");
            }
            return new Taxonomy(version, categories, parseRules(root.path("rules")));
        } catch (IOException e) {
            throw new IllegalStateException("读取 taxonomy.yml 失败", e);
        }
    }

    private Map<String, Map<String, List<String>>> parseCategories(JsonNode categoriesNode) {
        Map<String, Map<String, List<String>>> categories = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> roots = categoriesNode.fields();
        while (roots.hasNext()) {
            Map.Entry<String, JsonNode> rootEntry = roots.next();
            Map<String, List<String>> branches = new LinkedHashMap<>();
            if (rootEntry.getValue().isArray()) {
                branches.put("", parseLeaves(rootEntry.getValue()));
            } else {
                Iterator<Map.Entry<String, JsonNode>> branchNodes = rootEntry.getValue().fields();
                while (branchNodes.hasNext()) {
                    Map.Entry<String, JsonNode> branch = branchNodes.next();
                    branches.put(branch.getKey(), parseLeaves(branch.getValue()));
                }
            }
            categories.put(rootEntry.getKey(), branches);
        }
        return categories;
    }

    private Map<String, Taxonomy.Rule> parseRules(JsonNode rulesNode) {
        Map<String, Taxonomy.Rule> rules = new LinkedHashMap<>();
        if (rulesNode == null || !rulesNode.isObject()) {
            return rules;
        }
        Iterator<Map.Entry<String, JsonNode>> ruleNodes = rulesNode.fields();
        while (ruleNodes.hasNext()) {
            Map.Entry<String, JsonNode> ruleEntry = ruleNodes.next();
            JsonNode ruleNode = ruleEntry.getValue();
            rules.put(ruleEntry.getKey(), new Taxonomy.Rule(
                    parseLeaves(ruleNode.path("required")),
                    parseLeaves(ruleNode.path("recommended"))));
        }
        return rules;
    }

    private List<String> parseLeaves(JsonNode leavesNode) {
        List<String> leaves = new ArrayList<>();
        if (leavesNode != null && leavesNode.isArray()) {
            leavesNode.forEach(node -> leaves.add(node.asText()));
        }
        return leaves;
    }
}
