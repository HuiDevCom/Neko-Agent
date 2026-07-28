package org.agent.agent.service;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 结构化记忆条目
 */
public class MemoryEntry {

    public enum Type {
        PREFERENCE("偏好"),
        HABIT("习惯"),
        FACT("信息"),
        SKILL("技能"),
        SOCIAL("社交"),
        PROGRESS("进度"),
        PROJECT("项目");

        final String label;
        Type(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static Type fromString(String s) {
            for (Type t : values()) {
                if (t.name().equalsIgnoreCase(s)) return t;
            }
            return FACT;
        }
    }

    private Type type;
    private String content;
    private int confidence;   // 1-5
    private long createdAt;
    private long updatedAt;
    private final Set<String> sceneTags = Collections.synchronizedSet(new HashSet<>()); // 场景标签

    public MemoryEntry(Type type, String content, int confidence) {
        this.type = type;
        this.content = content;
        this.confidence = Math.max(1, Math.min(5, confidence));
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 添加场景标签 */
    public MemoryEntry tag(String... tags) {
        for (String t : tags) sceneTags.add(t.toLowerCase());
        return this;
    }

    public boolean matchesScene(String scene) {
        if (scene == null || scene.isEmpty()) return true;
        String s = scene.toLowerCase();
        for (String tag : sceneTags) {
            if (tag.equals(s) || content.toLowerCase().contains(s)) return true;
        }
        return false;
    }

    // Getters
    public Type getType() { return type; }
    public String getContent() { return content; }
    public int getConfidence() { return confidence; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public Set<String> getSceneTags() { return sceneTags; }

    /** 提升置信度（最多 5） */
    public void boost() {
        this.confidence = Math.min(5, this.confidence + 1);
        this.updatedAt = System.currentTimeMillis();
    }

    /** 衰减置信度 */
    public void decay() {
        this.confidence = Math.max(1, this.confidence - 1);
        this.updatedAt = System.currentTimeMillis();
    }

    public boolean isExpired() {
        long age = System.currentTimeMillis() - updatedAt;
        return confidence <= 1 && age > 60L * 24 * 60 * 60 * 1000; // 60 天
    }

    public boolean shouldSkipInjection() {
        long age = System.currentTimeMillis() - updatedAt;
        return confidence <= 1 && age > 30L * 24 * 60 * 60 * 1000; // 30 天
    }

    public String format() {
        String ts = new SimpleDateFormat("MM-dd").format(new Date(updatedAt));
        String stars = "★".repeat(confidence) + "☆".repeat(5 - confidence);
        return "[" + type.getLabel() + "|" + stars + "|" + ts + "] " + content;
    }

    public String formatShort() {
        return type.getLabel() + "：" + content;
    }

    // ==== 序列化 ====

    public java.util.Map<String, Object> serialize() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", type.name());
        map.put("content", content);
        map.put("confidence", confidence);
        map.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(createdAt)));
        map.put("updated_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(updatedAt)));
        if (!sceneTags.isEmpty()) {
            map.put("scene_tags", new java.util.ArrayList<>(sceneTags));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static MemoryEntry deserialize(java.util.Map<String, Object> map) {
        Type type = Type.fromString(String.valueOf(map.getOrDefault("type", "FACT")));
        String content = String.valueOf(map.getOrDefault("content", ""));
        int confidence = map.containsKey("confidence") ? ((Number) map.get("confidence")).intValue() : 3;
        MemoryEntry entry = new MemoryEntry(type, content, confidence);
        if (map.containsKey("created_at")) {
            try {
                entry.createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(String.valueOf(map.get("created_at"))).getTime();
            } catch (Exception ignored) {}
        }
        if (map.containsKey("updated_at")) {
            try {
                entry.updatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(String.valueOf(map.get("updated_at"))).getTime();
            } catch (Exception ignored) {}
        }
        if (map.containsKey("scene_tags")) {
            for (Object tag : (java.util.List<?>) map.get("scene_tags")) {
                entry.sceneTags.add(String.valueOf(tag).toLowerCase());
            }
        }
        return entry;
    }
}
