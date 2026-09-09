/**
 * The orchestration loop, what it costs, and what it wrote down.
 *
 * <p>This is where the model is kept on a leash. It chooses which tool to call and what to say; it
 * does not choose when to stop, what it may touch, or whether it may run again — all three are
 * counted here, before the call rather than after it.
 *
 * <p><strong>Every ceiling is a number in {@code ConversationLimits}, not configuration.</strong>
 * Five tool calls a turn, forty messages a conversation, a twenty-message window. Each of them
 * changes what a customer experiences, so each is a decision to be reviewed in a diff. What
 * <em>is</em> configuration — the provider, the key, the model, the timeout — is in
 * {@code AiProperties}, and the split is the same one {@code RateLimitProperties} and the
 * notification poller make.
 *
 * <p><strong>The system prompt is built here and stored nowhere.</strong> Rebuilt from the Business
 * row, its hours, its closures, its catalog and its FAQs on every turn, so it cannot drift between
 * turns and cannot be reconstructed by anybody reading {@code ai_messages}. Nothing a model produced
 * ever enters it.
 *
 * <p><strong>Transactions are small and deliberate.</strong> {@code ConversationStore} holds them,
 * as a separate bean rather than as annotations on the loop — a self-invoked {@code @Transactional}
 * method is not proxied, and the writes would have been silently unboundaried. A turn makes network
 * calls that take tens of seconds; one transaction around all of it would hold a connection for the
 * length of a conversation and would defeat "persisted before the next iteration", since nothing
 * uncommitted is readable by anyone debugging a stuck turn.
 */
package dev.reception.ai.application;
