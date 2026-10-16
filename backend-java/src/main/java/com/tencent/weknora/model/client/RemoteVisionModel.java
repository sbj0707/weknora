package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Go RemoteAPIVLM request ordering, float32 temperature and truncation contract. */
public final class RemoteVisionModel implements VisionModel {
  private final ChatModel chat;
  private final double temperature;

  public RemoteVisionModel(ChatModel chat, String configuredTemperature) {
    this.chat = chat;
    double t = (double) (float) 0.1;
    try {
      if (configuredTemperature != null && !configuredTemperature.isEmpty())
        t = Double.parseDouble(configuredTemperature);
    } catch (NumberFormatException ignored) {
    }
    temperature = t;
  }

  public String modelId() {
    return chat.modelId();
  }

  public String predict(CallContext context, List<byte[]> images, String prompt) {
    var json = SafeModelHttp.JSON;
    var parts =
        json.createArrayNode().add(json.createObjectNode().put("type", "text").put("text", prompt));
    for (byte[] image : images)
      if (image.length > 0) {
        String mime = mime(image);
        parts.add(
            json.createObjectNode()
                .put("type", "image_url")
                .set(
                    "image_url",
                    json.createObjectNode()
                        .put(
                            "url",
                            "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image))
                        .put("detail", "auto")));
      }
    var message = json.createObjectNode().put("role", "user");
    message.set("multi_content", parts);
    ObjectNode response;
    try {
      response =
          chat.chat(
              context,
              List.of(message),
              json.createObjectNode().put("temperature", temperature).put("max_tokens", 5000));
    } catch (CancellationException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new ModelClientException(
          "VLM request: "
              + (chat instanceof OpenAIChat ? "create chat completion: " : "")
              + error.getMessage());
    }
    String content = response.path("content").asText();
    if (content.replaceAll("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$", "").isEmpty()
        && response.path("finish_reason").asText().equals("length"))
      throw new ModelClientException(
          "VLM returned no content: completion truncated at 5000 tokens (finish_reason=length)");
    return content;
  }

  private static String mime(byte[] b) {
    if (b.length >= 8
        && b[0] == (byte) 137
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 13
        && b[5] == 10
        && b[6] == 26
        && b[7] == 10) return "image/png";
    if (b.length >= 3 && b[0] == (byte) 255 && b[1] == (byte) 216 && b[2] == (byte) 255)
      return "image/jpeg";
    if (b.length >= 6
        && (new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a")
            || new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")))
      return "image/gif";
    if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
    if (b.length >= 4 && b[0] == 0 && b[1] == 0 && (b[2] == 1 || b[2] == 2) && b[3] == 0)
      return "image/x-icon";
    if (b.length >= 14
        && b[12] == 'V'
        && b[13] == 'P'
        && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
      return "image/webp";
    return "image/png";
  }
}
