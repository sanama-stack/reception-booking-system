package dev.reception.ai.application.web;

import dev.reception.ai.application.AiConversation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's window into what the Receptionist has been saying.
 *
 * <p>Both a product feature and the primary debugging tool when it behaves oddly — which is why the
 * detail view shows tool calls with their arguments and results rather than only the prose. "It told
 * my customer the wrong price" is answerable from this screen and from nowhere else.
 *
 * <p>Read-only, {@code OWNER} and {@code ADMIN} only, declared once for the class.
 */
@RestController
@RequestMapping("/conversations")
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class ConversationQueryController {

    private final ConversationQueryService conversations;

    public ConversationQueryController(ConversationQueryService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    public ConversationResponses.ConversationPage list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ConversationResponses.ConversationPage.of(conversations.list(page, Math.min(size, 100)));
    }

    @GetMapping("/{id}")
    public ConversationResponses.ConversationDetail read(@PathVariable UUID id) {
        AiConversation conversation = conversations.read(id);
        return ConversationResponses.ConversationDetail.of(conversation, conversations.transcript(id));
    }
}
