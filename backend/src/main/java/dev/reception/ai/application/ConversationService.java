package dev.reception.ai.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ChatMessage;
import dev.reception.ai.port.ChatModel;
import dev.reception.ai.port.ChatModelException;
import dev.reception.ai.port.ChatResponse;
import dev.reception.ai.port.ChatRole;
import dev.reception.ai.port.ToolCall;
import dev.reception.ai.tools.AuthorizedAppointments;
import dev.reception.ai.tools.ToolContext;
import dev.reception.ai.tools.ToolRegistry;
import dev.reception.business.Business;
import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import dev.reception.common.ids.IdGenerator;
import dev.reception.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The orchestration loop: everything that happens between a customer pressing send and a reply
 * coming back.
 *
 * <p>The shape is docs/05-ai-architecture.md §4, and the properties worth stating are the ones a
 * reader cannot see from the code alone:
 *
 * <ul>
 *   <li><strong>Bounded twice.</strong> Five tool calls per turn and forty messages per
 *       conversation. Neither can be reached by anything the model does, because both are counted
 *       here rather than requested of it.
 *   <li><strong>The cap is checked before the call, never after.</strong> A cap enforced afterwards
 *       permits exactly one unbounded overrun.
 *   <li><strong>Persisted before use.</strong> Every model response and every tool result is written
 *       before the next iteration, so a crash mid-turn leaves a transcript that explains itself.
 *   <li><strong>Every failure lands on the Classic Flow.</strong> Provider outage, missing key,
 *       ceiling, cost cap, repeated tool errors — all of them become an error code the chat panel
 *       turns into a link. This works only because phase 08 shipped a genuinely complete
 *       alternative.
 * </ul>
 *
 * <p><strong>The model is never trusted with control flow.</strong> It decides which tool to call
 * and what to say; it does not decide when to stop, what it may touch, or whether it may run again.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /**
     * What the customer is told when the tool-call ceiling is hit.
     *
     * <p>A sentence rather than an error, because the conversation is still alive and the customer
     * did nothing wrong — the model went round in circles and the loop stopped it.
     */
    static final String TOOL_CEILING_FALLBACK =
            "Sorry — I'm having trouble getting that sorted. You can book directly on this page "
                    + "instead, or give us a call and we'll do it for you.";

    private final ChatModel chatModel;
    private final ToolRegistry tools;
    private final SystemPromptBuilder prompts;
    private final CostTracker costs;
    private final SessionTokens sessions;
    private final ConversationStore store;
    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final BusinessService businesses;
    private final TenantContext tenant;
    private final IdGenerator ids;
    private final Clock clock;

    public ConversationService(
            ChatModel chatModel,
            ToolRegistry tools,
            SystemPromptBuilder prompts,
            CostTracker costs,
            SessionTokens sessions,
            ConversationStore store,
            AiConversationRepository conversations,
            AiMessageRepository messages,
            BusinessService businesses,
            TenantContext tenant,
            IdGenerator ids,
            Clock clock) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.prompts = prompts;
        this.costs = costs;
        this.sessions = sessions;
        this.store = store;
        this.conversations = conversations;
        this.messages = messages;
        this.businesses = businesses;
        this.tenant = tenant;
        this.ids = ids;
        this.clock = clock;
    }

    /** A conversation and the token that continues it. The token is returned exactly once. */
    public record StartedConversation(UUID conversationId, String sessionToken) {}

    /**
     * Opens a conversation.
     *
     * @param seedAuthority an Appointment the customer already proved by arriving on a Manage Link,
     *     or empty. The only way authority exists before the first turn, and the reason a customer
     *     who followed a link from an email can say "move this" without reciting a code
     */
    public StartedConversation start(Optional<UUID> seedAuthority) {
        Business business = requireReceptionistAvailable();

        String token = sessions.issue();
        AiConversation conversation = new AiConversation(
                ids.newId(),
                business.getId(),
                sessions.hash(token),
                seedAuthority.map(List::of).orElseGet(List::of),
                clock.instant());
        store.create(conversation);

        return new StartedConversation(conversation.getId(), token);
    }

    /**
     * One turn.
     *
     * <p>Not {@code @Transactional} as a whole, and that is deliberate rather than an oversight. A
     * turn makes network calls that can take tens of seconds, and holding a database transaction
     * open across them would tie up a connection for the length of a conversation. The writes inside
     * it are individually transactional — which is also what makes "persisted before the next
     * iteration" true, since an open outer transaction would leave the trail invisible until the
     * turn ended.
     */
    public ConversationTurn respond(String sessionToken, String userText) {
        Business business = requireReceptionistAvailable();
        AiConversation conversation = requireActive(sessionToken);

        if (conversation.messageCount() >= ConversationLimits.MAX_MESSAGES_PER_CONVERSATION) {
            store.close(conversation.businessId(), conversation.getId(), ConversationStatus.CLOSED);
            throw new ApiException(
                    ErrorCode.AI_LIMIT_REACHED,
                    "This conversation has gone on as long as I can manage. You can book directly on "
                            + "this page, or start a new chat.");
        }
        if (!costs.withinCap(business.getId(), business.timezone(), business.aiDailyCostCapCents())) {
            // Before any model call, so exceeding the cap costs nothing. The conversation is marked
            // rather than closed silently, and the owner sees it in the dashboard.
            store.close(conversation.businessId(), conversation.getId(), ConversationStatus.LIMIT_REACHED);
            throw new ApiException(
                    ErrorCode.AI_LIMIT_REACHED,
                    "The assistant is unavailable for the rest of today. You can still book directly "
                            + "on this page.");
        }

        return runTurn(business, conversation, userText);
    }

    private ConversationTurn runTurn(Business business, AiConversation conversation, String userText) {
        UUID businessId = business.getId();
        UUID conversationId = conversation.getId();

        AuthorizedAppointments authority = new AuthorizedAppointments(conversation.authorizedAppointmentIds());
        ToolContext toolContext = new ToolContext(businessId, conversationId, authority, clock);

        // The user's message is persisted first, so a turn that fails anywhere below still shows
        // what was asked. A transcript missing the question is not a transcript.
        store.append(AiMessage.user(ids.newId(), conversationId, businessId, userText, clock.instant()));

        List<ChatMessage> context = new ArrayList<>();
        context.add(ChatMessage.system(prompts.build()));
        context.addAll(window(businessId, conversationId));

        int promptTokens = 0;
        int completionTokens = 0;
        int persistedMessages = 1;
        ObjectNode appointmentCreated = null;
        String reply = TOOL_CEILING_FALLBACK;

        // Counts tool CALLS, not iterations, and the difference is the whole of the ceiling. A model
        // that asks for six tools in a single response would sail past a loop that only counted
        // trips round — one iteration, six executions — which is exactly the runaway the limit
        // exists to stop. Budgeted this way, "five tool calls per turn" is true however the model
        // chooses to group them.
        int toolCallsMade = 0;

        while (true) {
            if (toolCallsMade >= ConversationLimits.MAX_TOOL_CALLS_PER_TURN) {
                // The budget is spent. reply is still TOOL_CEILING_FALLBACK, which is the polite
                // hand-off to the Classic Flow docs/05-ai-architecture.md §8 prescribes.
                log.info("Tool-call ceiling reached for conversation {}", conversationId);
                break;
            }

            ChatResponse response;
            try {
                response = chatModel.complete(context, tools.specs());
            } catch (ChatModelException e) {
                log.warn("Model call failed for conversation {}", conversationId, e);
                // The conversation stays ACTIVE and resumable: an outage is not the customer's
                // fault and retrying is the right thing for them to do.
                store.recordTurn(
                        businessId, conversationId, persistedMessages, promptTokens, completionTokens, authority);
                throw new ApiException(
                        ErrorCode.AI_UNAVAILABLE,
                        "I can't reach the booking assistant just now. You can book directly on this "
                                + "page instead.");
            }

            promptTokens += response.usage().promptTokens();
            completionTokens += response.usage().completionTokens();

            store.append(AiMessage.assistant(ids.newId(), conversationId, businessId, response.text(), clock.instant()));
            persistedMessages++;

            if (!response.hasToolCalls()) {
                reply = response.text() == null ? "" : response.text();
                break;
            }

            context.add(ChatMessage.assistantToolCalls(response.text(), response.toolCalls()));

            for (ToolCall call : response.toolCalls()) {
                if (toolCallsMade >= ConversationLimits.MAX_TOOL_CALLS_PER_TURN) {
                    // Mid-response. The calls already executed stand and are persisted; the rest are
                    // simply not made, and the loop hands off on its next pass. Executing all six
                    // "because they arrived together" would make the ceiling advisory.
                    break;
                }
                toolCallsMade++;

                ObjectNode result = tools.execute(call.name(), call.arguments(), toolContext);

                store.append(AiMessage.tool(
                        ids.newId(),
                        conversationId,
                        businessId,
                        call.name(),
                        call.id(),
                        call.arguments(),
                        result,
                        clock.instant()));
                persistedMessages++;

                context.add(ChatMessage.toolResult(call.id(), result));

                // Captured from the tool result, never parsed out of the model's text. This is the
                // object the confirmation card is rendered from.
                if ("create_appointment".equals(call.name()) && !result.has("error")) {
                    appointmentCreated = result;
                }
            }
        }

        AiConversation updated = store.recordTurn(
                businessId, conversationId, persistedMessages, promptTokens, completionTokens, authority);

        // Asked of the row the store just wrote, not of the copy this method has been holding since
        // before the model calls — which is stale by exactly the messages this turn added.
        int remaining =
                Math.max(0, ConversationLimits.MAX_MESSAGES_PER_CONVERSATION - updated.messageCount());
        ConversationStatus status = ConversationStatus.ACTIVE;
        if (remaining == 0) {
            store.close(businessId, conversationId, ConversationStatus.CLOSED);
            status = ConversationStatus.CLOSED;
        }
        return new ConversationTurn(conversationId, reply, appointmentCreated, status, remaining);
    }

    /**
     * The last twenty messages, oldest first.
     *
     * <p>Read back out of the database rather than held in memory across turns, which is what makes
     * a conversation resumable by any instance and after any restart. Tool rows become tool results
     * again; the pairing with the assistant message that requested them is not reconstructed,
     * because the model needs the results and their order, not the shape of a previous turn's
     * bookkeeping.
     */
    private List<ChatMessage> window(UUID businessId, UUID conversationId) {
        List<AiMessage> recent = new ArrayList<>(messages.findByBusinessIdAndConversationIdOrderByCreatedAtDesc(
                businessId, conversationId, PageRequest.of(0, ConversationLimits.CONTEXT_WINDOW_MESSAGES)));
        Collections.reverse(recent);

        List<ChatMessage> window = new ArrayList<>(recent.size());
        for (AiMessage message : recent) {
            if (message.role() == ChatRole.USER) {
                window.add(ChatMessage.user(message.content()));
            } else if (message.role() == ChatRole.ASSISTANT && message.content() != null) {
                window.add(ChatMessage.assistant(message.content()));
            } else if (message.role() == ChatRole.TOOL) {
                // As an assistant note rather than a TOOL message: a tool result with no matching
                // tool call in the same request is rejected by the provider, and the call that
                // produced this one belongs to a turn that has already ended.
                window.add(ChatMessage.assistant(
                        "[" + message.toolName() + " returned: " + message.toolResult() + "]"));
            }
        }
        return window;
    }

    private AiConversation requireActive(String sessionToken) {
        AiConversation conversation = conversations
                .findByBusinessIdAndSessionTokenHash(tenant.businessId(), sessions.hash(sessionToken))
                // A 404 rather than a 401: an unknown session token and one belonging to another
                // business are the same answer, as everywhere else (docs/06-security.md §3).
                .orElseThrow(() -> ApiException.notFound("That conversation has ended. Start a new one."));

        if (conversation.status() != ConversationStatus.ACTIVE) {
            throw new ApiException(
                    ErrorCode.AI_LIMIT_REACHED,
                    "This conversation has ended. You can book directly on this page, or start a new chat.");
        }
        return conversation;
    }

    /**
     * Whether there is a Receptionist to talk to at all.
     *
     * <p>Two ways there is not, and they produce the same code because the customer's options are
     * the same either way: the owner switched it off, or nobody configured a key. The second is the
     * ordinary state of a fresh clone, and it must not be a startup failure — a developer with no
     * API key should get a working application with a working Classic Flow.
     */
    private Business requireReceptionistAvailable() {
        Business business = businesses.read();
        if (!business.aiEnabled()) {
            throw new ApiException(
                    ErrorCode.AI_UNAVAILABLE,
                    "The booking assistant isn't available. You can book directly on this page.");
        }
        return business;
    }
}
