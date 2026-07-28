package org.agent.agent.service;

import com.google.gson.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OpenAIService {

    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    private String apiKey;
    private String baseUrl;
    private String model;
    private int maxTokens;
    private double temperature;
    private int timeoutSeconds;

    public OpenAIService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        loadConfig();
    }

    public void loadConfig() {
        plugin.reloadConfig();
        var config = plugin.getConfig();
        this.apiKey = config.getString("openai.api_key", "");
        this.baseUrl = config.getString("openai.base_url", "https://api.openai.com");
        // 去掉末尾的斜杠
        if (this.baseUrl.endsWith("/")) {
            this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        }
        this.model = config.getString("openai.model", "gpt-4o");
        this.maxTokens = config.getInt("openai.max_tokens", 1024);
        this.temperature = config.getDouble("openai.temperature", 0.7);
        this.timeoutSeconds = config.getInt("openai.timeout_seconds", 60);
    }

    /**
     * 异步调用 OpenAI Chat Completion API（自动重试）
     * @param messages 完整的消息列表（包括 system prompt 和历史对话）
     * @return AI 回复文本的 CompletableFuture
     */
    public CompletableFuture<String> chat(List<ChatMessage> messages) {
        return CompletableFuture.supplyAsync(() -> {
            JsonArray messagesArray = new JsonArray();
            for (ChatMessage msg : messages) {
                JsonObject obj = new JsonObject();
                obj.addProperty("role", msg.role());
                obj.addProperty("content", msg.content());
                messagesArray.add(obj);
            }

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.add("messages", messagesArray);
            requestBody.addProperty("max_tokens", maxTokens);
            requestBody.addProperty("temperature", temperature);

            String url = baseUrl + "/v1/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            // 重试：最多尝试 3 次（首次 + 2 次重试）
            int maxAttempts = 3;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (attempt > 1) {
                        plugin.getLogger().info("重试第 " + attempt + " 次，模型: " + model);
                    } else {
                        plugin.getLogger().info("发送请求到 OpenAI: " + model);
                    }

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() != 200) {
                        plugin.getLogger().warning("OpenAI API 返回错误 " + response.statusCode() + ": " + response.body());
                        // 4xx 错误（如认证失败）不重试
                        if (response.statusCode() >= 400 && response.statusCode() < 500) {
                            return "";
                        }
                        // 5xx 错误重试
                        plugin.getLogger().info("服务器错误，将重试...");
                        Thread.sleep(2000L * attempt);
                        continue;
                    }

                    JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonArray choices = responseJson.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        plugin.getLogger().warning("OpenAI 返回空 choices，将重试...");
                        Thread.sleep(2000L * attempt);
                        continue;
                    }

                    JsonObject firstChoice = choices.get(0).getAsJsonObject();
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    return message.get("content").getAsString();

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "";
                } catch (Exception e) {
                    plugin.getLogger().warning("请求失败（第 " + attempt + "/" + maxAttempts + " 次）: " + e.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(2000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return "";
                        }
                    }
                }
            }

            plugin.getLogger().severe("OpenAI API 请求在 " + maxAttempts + " 次尝试后全部失败");
            return "";
        });
    }

    /**
     * 聊天消息记录
     */
    public record ChatMessage(String role, String content) {
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }
}
