package dev.reception.ai.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What differs by environment, and nothing that differs by judgement.
 *
 * <p>The split is the same one {@code RateLimitProperties} and the notification poller make: the
 * provider, the key, the model and the timeout are configuration, because they change between a
 * laptop and production. The <strong>ceilings</strong> are not here — five tool calls a turn, forty
 * messages a conversation, a twenty-message window — because a number that changes what a customer
 * experiences should be reviewed rather than configured. Those live in
 * {@link ConversationLimits}.
 *
 * <p>{@code apiKey} is empty by default and the {@code local} profile leaves it as the placeholder
 * from {@code .env.example}. That is deliberate: a developer who never sets one gets a Receptionist
 * that degrades to the Classic Flow, which is a working application rather than a failed startup.
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * How long to wait for a completion before giving up and degrading.
     *
     * <p>Thirty seconds because a turn with tool calls makes more than one round trip and a
     * customer watching a typing indicator will wait about that long. Past it, the honest answer is
     * the Classic Flow rather than a longer wait.
     */
    private int timeoutSeconds = 30;

    /**
     * Cost per million tokens, in cents, for the configured model.
     *
     * <p>Held here rather than fetched, because there is no endpoint that reports it and a wrong
     * number would only ever make the cap fire early or late. Defaults are gpt-4o-mini's published
     * prices; a different model needs these set alongside it, and {@code estimated_cost_cents} says
     * "estimated" for this reason.
     */
    private int promptCentsPerMillionTokens = 15;

    private int completionCentsPerMillionTokens = 60;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getPromptCentsPerMillionTokens() {
        return promptCentsPerMillionTokens;
    }

    public void setPromptCentsPerMillionTokens(int promptCentsPerMillionTokens) {
        this.promptCentsPerMillionTokens = promptCentsPerMillionTokens;
    }

    public int getCompletionCentsPerMillionTokens() {
        return completionCentsPerMillionTokens;
    }

    public void setCompletionCentsPerMillionTokens(int completionCentsPerMillionTokens) {
        this.completionCentsPerMillionTokens = completionCentsPerMillionTokens;
    }

    /**
     * Whether the Receptionist can run at all.
     *
     * <p>An absent key is not a misconfiguration to fail on. It is the ordinary state of a clone
     * that has not been given one, and the correct behaviour is the same as a provider outage:
     * degrade to the Classic Flow and say so.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-local-dev-only");
    }
}
