package org.agent.agent.service;

import com.google.gson.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Kimi 联网搜索服务 —— 通过 $web_search builtin_function 实现
 *
 * 工作流程：
 * 1. 携带 $web_search 工具发起请求
 * 2. 若 finish_reason=tool_calls，把 arguments 原样回传
 * 3. 拿到 finish_reason=stop 的最终回复
 */
public class KimiSearchService {

    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    private String apiKey;
    private String baseUrl;
    private String model;
    private int maxTokens;
    private int timeoutSeconds;
    private boolean enabled;

    public KimiSearchService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        loadConfig();
    }

    public void loadConfig() {
        plugin.reloadConfig();
        var config = plugin.getConfig();
        this.apiKey = config.getString("kimi.api_key", "");
        this.baseUrl = config.getString("kimi.base_url", "https://api.moonshot.cn");
        if (this.baseUrl.endsWith("/")) {
            this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        }
        this.model = config.getString("kimi.model", "kimi-k2.5");
        this.maxTokens = config.getInt("kimi.max_tokens", 4096);
        this.timeoutSeconds = config.getInt("kimi.timeout_seconds", 60);
        this.enabled = config.getBoolean("kimi.enabled", false);

        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.contains("your-"))) {
            plugin.getLogger().warning("Kimi 联网搜索已启用但未配置有效的 api_key，请编辑 config.yml 的 kimi.api_key");
            this.enabled = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 调用 Kimi 联网搜索，返回搜索结果摘要文本。
     * @param query 搜索关键词（建议完整自然语言问题）
     * @return 搜索摘要文本，失败时返回错误说明字符串
     */
    public String search(String query) {
        if (!enabled) {
            return "[联网搜索未启用]";
        }

        try {
            String url = baseUrl + "/v1/chat/completions";

            // 构造 messages
            JsonArray messagesArray = new JsonArray();
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", query);
            messagesArray.add(userMsg);

            // 构造 tools：声明 $web_search
            JsonArray toolsArray = new JsonArray();
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("type", "builtin_function");
            JsonObject functionObj = new JsonObject();
            functionObj.addProperty("name", "$web_search");
            toolObj.add("function", functionObj);
            toolsArray.add(toolObj);

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.add("messages", messagesArray);
            requestBody.add("tools", toolsArray);
            requestBody.addProperty("max_tokens", maxTokens);

            // 第一轮：模型决定是否搜索
            JsonObject firstResponse = sendRequest(url, requestBody);
            if (firstResponse == null) return "[Kimi 调用失败]";

            JsonArray choices = firstResponse.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return "[Kimi 未返回任何内容]";
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            String finishReason = firstChoice.has("finish_reason") && !firstChoice.get("finish_reason").isJsonNull()
                    ? firstChoice.get("finish_reason").getAsString()
                    : "stop";

            // 不需要联网（直接回复）
            if (!"tool_calls".equals(finishReason)) {
                JsonObject message = firstChoice.getAsJsonObject("message");
                if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                    return message.get("content").getAsString();
                }
                return "[Kimi 直接回复为空]";
            }

            // 需要联网：把 assistant message 加入上下文，处理所有 tool_calls
            JsonObject assistantMessage = firstChoice.getAsJsonObject("message");
            messagesArray.add(assistantMessage);

            JsonArray toolCalls = assistantMessage.has("tool_calls") && !assistantMessage.get("tool_calls").isJsonNull()
                    ? assistantMessage.getAsJsonArray("tool_calls")
                    : new JsonArray();

            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolCallId = toolCall.get("id").getAsString();
                JsonObject function = toolCall.getAsJsonObject("function");

                String toolResult;
                String name = "";
                if (function == null) {
                    toolResult = "{\"error\": \"missing function in tool_call\"}";
                } else {
                    name = function.get("name").getAsString();
                    String arguments = function.get("arguments").getAsString();
                    if ("$web_search".equals(name)) {
                        toolResult = arguments;
                    } else {
                        toolResult = "{\"error\": \"unknown tool: " + name + "\"}";
                    }
                }

                JsonObject toolMsg = new JsonObject();
                toolMsg.addProperty("role", "tool");
                toolMsg.addProperty("tool_call_id", toolCallId);
                toolMsg.addProperty("name", name);
                toolMsg.addProperty("content", toolResult);
                messagesArray.add(toolMsg);
            }

            // 第二轮：让模型基于搜索结果生成最终回复
            JsonObject secondResponse = sendRequest(url, requestBody);
            if (secondResponse == null) return "[Kimi 调用失败]";

            JsonArray secondChoices = secondResponse.getAsJsonArray("choices");
            if (secondChoices == null || secondChoices.isEmpty()) {
                return "[Kimi 第二次调用未返回内容]";
            }

            JsonObject finalMessage = secondChoices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (finalMessage != null && finalMessage.has("content") && !finalMessage.get("content").isJsonNull()) {
                return finalMessage.get("content").getAsString();
            }
            return "[Kimi 最终回复为空]";

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Kimi 联网搜索异常", e);
            return "[Kimi 搜索异常: " + e.getMessage() + "]";
        }
    }

    /**
     * 发送 HTTP 请求到 Kimi
     */
    private JsonObject sendRequest(String url, JsonObject requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            plugin.getLogger().warning("Kimi API 返回 " + response.statusCode() + ": " + response.body());
            return null;
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
