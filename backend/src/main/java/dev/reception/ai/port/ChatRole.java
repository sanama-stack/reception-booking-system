package dev.reception.ai.port;

/**
 * The four roles a message in a model conversation can have.
 *
 * <p>{@code SYSTEM} appears only in the list handed to a model call — it is rebuilt from
 * configuration every turn and never persisted, so {@code ai_messages} constrains its {@code role}
 * to the other three (docs/05-ai-architecture.md §4).
 */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
