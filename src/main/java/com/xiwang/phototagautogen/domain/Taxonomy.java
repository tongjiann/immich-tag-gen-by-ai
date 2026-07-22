package com.xiwang.phototagautogen.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class Taxonomy {
    private final int version;
    private final Map<String, Map<String, List<String>>> categories;
    private final Map<String, Rule> rules;
    private final Set<String> allowedPaths;

    public Taxonomy(int version, Map<String, Map<String, List<String>>> categories) {
        this(version, categories, Map.of());
    }

    public Taxonomy(int version, Map<String, Map<String, List<String>>> categories,
                    Map<String, Rule> rules) {
        if (version < 1) {
            throw new IllegalArgumentException("词表版本必须大于 0");
        }
        validateCategories(categories);
        this.version = version;
        this.categories = deepCopy(categories);
        this.rules = deepCopyRules(rules);
        validateRules(this.categories, this.rules);
        this.allowedPaths = buildAllowedPaths(this.categories);
    }

    public int version() { return version; }

    public Map<String, Map<String, List<String>>> categories() { return categories; }

    public Map<String, Rule> rules() { return rules; }

    public boolean isAllowed(TagPath path) { return allowedPaths.contains(path.toString()); }

    public List<String> allowedPathStrings() {
        return List.copyOf(allowedPaths);
    }

    public List<String> requiredBranches(String root) {
        return rules.getOrDefault(root, Rule.EMPTY).required();
    }

    public List<String> recommendedBranches(String root) {
        return rules.getOrDefault(root, Rule.EMPTY).recommended();
    }

    public void validateRequiredBranches(String root, Collection<TagPath> paths) {
        List<String> missingBranches = missingRequiredBranches(root, paths);
        if (!missingBranches.isEmpty()) {
            throw new IllegalArgumentException(root + "模型结果缺少必选分类: "
                    + String.join("、", missingBranches));
        }
    }

    public List<String> missingRequiredBranches(String root, Collection<TagPath> paths) {
        Set<String> presentBranches = new LinkedHashSet<>();
        paths.stream()
                .filter(path -> root.equals(path.root()) && path.segments().size() >= 3)
                .map(path -> String.join("/", path.segments().subList(1, path.segments().size() - 1)))
                .forEach(presentBranches::add);
        return requiredBranches(root).stream()
                .filter(branch -> !presentBranches.contains(branch))
                .toList();
    }

    public String promptText() {
        return categories.entrySet().stream()
                .map(root -> root.getKey() + ": " + root.getValue().entrySet().stream()
                        .map(branch -> branch.getKey().isBlank()
                                ? String.join("、", branch.getValue())
                                : branch.getKey() + "（" + String.join("、", branch.getValue()) + "）")
                        .reduce((a, b) -> a + "；" + b).orElse(""))
                .reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static Set<String> buildAllowedPaths(Map<String, Map<String, List<String>>> categories) {
        Set<String> paths = new LinkedHashSet<>();
        categories.forEach((root, branches) -> branches.forEach((branch, leaves) ->
                leaves.forEach(leaf -> paths.add(branch.isBlank()
                        ? root + "/" + leaf
                        : root + "/" + branch + "/" + leaf))));
        return Collections.unmodifiableSet(paths);
    }

    private static void validateCategories(Map<String, Map<String, List<String>>> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new IllegalArgumentException("词表必须至少包含一个一级分类");
        }
        categories.forEach((root, branches) -> {
            validateSegment(root, "一级分类");
            if (branches == null || branches.isEmpty()) {
                throw new IllegalArgumentException("一级分类未配置标签: " + root);
            }
            branches.forEach((branch, leaves) -> {
                if (branch == null) {
                    throw new IllegalArgumentException("二级分类不能为 null: " + root);
                }
                if (!branch.isBlank()) {
                    validateBranchName(branch);
                }
                if (leaves == null || leaves.isEmpty()) {
                    throw new IllegalArgumentException("分类未配置叶子标签: " + root + "/" + branch);
                }
                LinkedHashSet<String> distinctLeaves = new LinkedHashSet<>();
                leaves.forEach(leaf -> {
                    validateSegment(leaf, "叶子标签");
                    if (!distinctLeaves.add(leaf)) {
                        throw new IllegalArgumentException("分类包含重复叶子标签: "
                                + root + "/" + branch + "/" + leaf);
                    }
                });
            });
        });
    }

    private static void validateBranchName(String branch) {
        String[] segments = branch.split("/", -1);
        if (segments.length > 2) {
            throw new IllegalArgumentException("二级分类最多包含两个路径层级: " + branch);
        }
        for (String segment : segments) {
            validateSegment(segment, "二级分类");
        }
    }

    private static void validateSegment(String value, String segmentName) {
        if (value == null || value.isBlank() || value.contains("/")) {
            throw new IllegalArgumentException(segmentName + "必须为不含斜杠的非空文本");
        }
    }

    private static Map<String, Map<String, List<String>>> deepCopy(
            Map<String, Map<String, List<String>>> source) {
        Map<String, Map<String, List<String>>> result = new LinkedHashMap<>();
        source.forEach((root, branches) -> {
            Map<String, List<String>> copiedBranches = new LinkedHashMap<>();
            branches.forEach((branch, leaves) -> copiedBranches.put(branch, List.copyOf(leaves)));
            result.put(root, Collections.unmodifiableMap(copiedBranches));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Rule> deepCopyRules(Map<String, Rule> source) {
        Map<String, Rule> result = new LinkedHashMap<>();
        source.forEach((root, rule) -> result.put(root, new Rule(rule.required(), rule.recommended())));
        return Collections.unmodifiableMap(result);
    }

    private static void validateRules(Map<String, Map<String, List<String>>> categories,
                                      Map<String, Rule> rules) {
        rules.forEach((root, rule) -> {
            Map<String, List<String>> branches = categories.get(root);
            if (branches == null) {
                throw new IllegalArgumentException("规则引用了不存在的一级分类: " + root);
            }
            rule.required().forEach(branch -> validateBranch(root, branches, branch));
            rule.recommended().forEach(branch -> validateBranch(root, branches, branch));
            Set<String> classifiedBranches = new LinkedHashSet<>(rule.required());
            classifiedBranches.addAll(rule.recommended());
            Set<String> taxonomyBranches = branches.keySet().stream()
                    .filter(branch -> !branch.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!classifiedBranches.equals(taxonomyBranches)) {
                throw new IllegalArgumentException("规则未完整覆盖二级分类: " + root);
            }
        });
    }

    private static void validateBranch(String root, Map<String, List<String>> branches, String branch) {
        if (!branches.containsKey(branch) || branch.isBlank()) {
            throw new IllegalArgumentException("规则引用了不存在的二级分类: " + root + "/" + branch);
        }
    }

    public record Rule(List<String> required, List<String> recommended) {
        private static final Rule EMPTY = new Rule(List.of(), List.of());

        public Rule {
            required = distinctNonBlank(required, "required");
            recommended = distinctNonBlank(recommended, "recommended");
            if (!Collections.disjoint(required, recommended)) {
                throw new IllegalArgumentException("必选和建议分类不能重复");
            }
        }

        private static List<String> distinctNonBlank(List<String> values, String fieldName) {
            if (values == null) {
                throw new IllegalArgumentException("规则字段不能为空: " + fieldName);
            }
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            values.forEach(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("规则分类不能为空: " + fieldName);
                }
                distinct.add(value);
            });
            return List.copyOf(distinct);
        }
    }
}
