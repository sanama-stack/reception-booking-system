package dev.reception.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.application.AiProperties;
import dev.reception.ai.port.ChatMessage;
import dev.reception.ai.port.ChatModel;
import dev.reception.ai.port.ChatModelException;
import dev.reception.ai.port.ChatResponse;
import dev.reception.ai.port.ChatRole;
import dev.reception.ai.port.TokenUsage;
import dev.reception.ai.port.ToolCall;
import dev.reception.ai.port.ToolSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The only class in the application that knows OpenAI exists.
 *
 * <p>Enforced by an ArchUnit rule, not merely intended: {@code AiProviderIsolationTest} fails the
 * build if any other class mentions the provider. Everything above this speaks {@link ChatMessage},
 * {@link ToolSpec} and {@link ChatResponse}, which is what lets the entire orchestration loop be
 * tested against a scripted double with no network and no cost.
 *
 * <p><strong>A hand-written {@code RestClient} call rather than an SDK</strong> (ADR-0009). The port
 * above already provides the seam an SDK's abstraction would provide, the chat-completions payload
 * is small enough to read in one screen, and the strict-mode schemas are built by
 * {@code ToolSchemas} in exactly the shape the wire wants — so a typed client would be a dependency
 * whose types stop at this file's boundary anyway.
 *
 * <p><strong>Every failure becomes {@link ChatModelException}</strong>, including a body that parses
 * but is not the shape expected. The caller has one response to all of them and there is nothing to
 * be gained by distinguishing a timeout from a 500 two layers below the customer.
 */
@Component
public class OpenAiChatModel implements ChatModel {

    private final AiProperties properties;
    private final ObjectMapper json;
    private final RestClient http;

    public OpenAiChatModel(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // Both, because a connect timeout alone leaves a hung read waiting forever — which is the
        // failure that actually happens, and the one a customer experiences as a typing indicator
        // that never stops.
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

        this.http = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public ChatResponse complete(List<ChatMessage> messages, List<ToolSpec> tools) {
        if (!properties.isConfigured()) {
            // Not an IllegalStateException. A clone with no API key is the ordinary state of this
            // repository, and the correct behaviour is the same as a provider outage: degrade to
            // the Classic Flow. A startup failure would make the whole application unrunnable
            // without a paid account.
            throw new ChatModelException("No OpenAI API key is configured");
        }

        JsonNode body;
        try {
            body = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request(messages, tools))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // The message, never the response body. An upstream error can quote the request back,
            // and the request contains the conversation.
            throw new ChatModelException("OpenAI request failed: " + e.getClass().getSimpleName(), e);
        }

        return parse(body);
    }

    private ObjectNode request(List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode request = json.createObjectNode();
        request.put("model", properties.getModel());

        ArrayNode wire = request.putArray("messages");
        for (ChatMessage message : messages) {
            wire.add(encode(message));
        }

        if (!tools.isEmpty()) {
            ArrayNode published = request.putArray("tools");
            for (ToolSpec tool : tools) {
                ObjectNode entry = published.addObject();
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.parameters());
                // Strict mode. This is the line that removes malformed-argument handling from the
                // runtime rather than making us validate defensively afterwards — and it only holds
                // because ToolSchemas builds schemas that satisfy its requirements.
                function.put("strict", true);
            }
            request.put("tool_choice", "auto");
        }

        return request;
    }

    private ObjectNode encode(ChatMessage message) {
        ObjectNode encoded = json.createObjectNode();
        encoded.put("role", message.role().name().toLowerCase(java.util.Locale.ROOT));

        if (message.role() == ChatRole.TOOL) {
            encoded.put("tool_call_id", message.toolCallId());
            encoded.put("content", message.content());
            return encoded;
        }

        if (message.content() == null) {
            encoded.putNull("content");
        } else {
            encoded.put("content", message.content());
        }

        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = encoded.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode entry = calls.addObject();
                entry.put("id", call.id());
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", call.name());
                // A JSON *string*, not an object. The wire format nests a serialised document here,
                // and sending the object instead is accepted by nothing and fails as a 400.
                function.put("arguments", call.arguments().toString());
            }
        }
        return encoded;
    }

    private ChatResponse parse(JsonNode body) {
        if (body == null || !body.has("choices") || body.get("choices").isEmpty()) {
            throw new ChatModelException("OpenAI returned no choices");
        }

        JsonNode message = body.get("choices").get(0).path("message");
        TokenUsage usage = new TokenUsage(
                body.path("usage").path("prompt_tokens").asInt(0),
                body.path("usage").path("completion_tokens").asInt(0));

        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>(toolCalls.size());
            for (JsonNode call : toolCalls) {
                JsonNode function = call.path("function");
                calls.add(new ToolCall(
                        call.path("id").asText(),
                        function.path("name").asText(),
                        parseArguments(function.path("arguments").asText())));
            }
            return ChatResponse.toolCalls(calls, usage);
        }

        JsonNode content = message.path("content");
        return ChatResponse.text(content.isNull() ? "" : content.asText(), usage);
    }

    /**
     * The arguments string, back into a document.
     *
     * <p>Under strict mode this cannot fail against a live model. It is still handled, because "the
     * provider guarantees it" is exactly the assumption that produces an unlogged
     * {@code NullPointerException} the first time a proxy, a cache or a compatible-but-different
     * endpoint sits in the middle — {@code baseUrl} is configuration, so one can.
     */
    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(arguments);
        } catch (JsonProcessingException e) {
            throw new ChatModelException("OpenAI returned tool arguments that are not JSON", e);
        }
    }
}
