/**
 * The model-facing port, and nothing that knows a provider exists.
 *
 * <p>Everything here is a value type or an interface. {@code dev.reception.ai.openai} is the only
 * package permitted to depend on a provider's wire format, and an ArchUnit rule enforces it — so
 * these types are what the orchestration loop, the tools and every test speak, and the adapter is
 * a detail one directory away.
 */
package dev.reception.ai.port;
