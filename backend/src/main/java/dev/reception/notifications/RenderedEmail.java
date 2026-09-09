package dev.reception.notifications;

/**
 * A message as text, before it is anybody's row or anybody's email.
 *
 * <p>The output of {@code EmailTemplateRenderer} and the input to both {@link Notification} and
 * {@link EmailSender}. Keeping it a value rather than rendering straight into an entity is what lets
 * the template tests assert on rendering without a database, and what lets the poller send a row it
 * did not render.
 *
 * <p>Both bodies, always. A message with only HTML is unreadable to a text client and reads as spam
 * to several filters; a {@code multipart/alternative} with both is the shape a real transactional
 * email has.
 */
public record RenderedEmail(String subject, String html, String text) {}
