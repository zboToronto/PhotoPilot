package com.photopilot.validation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * One shared reading of recorded F05 specification text for UC14 and the F06 inventory report. Placeholder entries
 * such as "n/a", "-" or "未知" record that a value is not known; two equal placeholders therefore never prove that
 * equipment fits. Stored records are not changed: the policy applies only when values are compared.
 */
public final class SpecificationValues {
    private static final Set<String> PLACEHOLDERS = Set.of("", "unknown", "unspecified", "unsure", "not sure", "n/a", "na",
        "n.a.", "none", "null", "nil", "tbd", "other", "?", "??", "???", "-", "--", "---", "–", "—", "/", "...", "…",
        "未知", "不详", "不明", "不清楚", "未填写", "未指定", "无", "暂无", "其他", "其它");

    private SpecificationValues() { }

    /** True when the value is blank or a placeholder (case, surrounding/internal spacing and full-width forms ignored). */
    public static boolean unknown(String value) {
        if (value == null) return true;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        return PLACEHOLDERS.contains(normalized) || PLACEHOLDERS.contains(normalized.replaceAll("\\s+", ""));
    }
}
