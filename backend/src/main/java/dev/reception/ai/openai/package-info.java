/**
 * The provider, and the only place in the application that knows there is one.
 *
 * <p>One class. Everything above it speaks {@code ChatMessage}, {@code ToolSpec} and
 * {@code ChatResponse}, which is what lets the entire orchestration loop be tested against a
 * scripted double with no network and no cost — the reason the port was drawn in the first place
 * (docs/05-ai-architecture.md §10).
 *
 * <p>No SDK: the request is built with Spring's {@code RestClient} and read with Jackson
 * (ADR-0009). That does not weaken the isolation rule, it changes what breaking it looks like — a
 * leak is now a second class that knows the wire format rather than a second class with an import.
 * {@code AiProviderIsolationTest} asserts all three halves of it: nothing outside this package
 * depends on the adapter, nothing else in the AI layer speaks HTTP, and nothing else reads the API
 * key.
 */
package dev.reception.ai.openai;
