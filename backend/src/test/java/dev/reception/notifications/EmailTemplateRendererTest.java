package dev.reception.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the Customer actually reads.
 *
 * <p>No Spring, no database — the renderer takes a {@link MailModel} and returns strings, which is
 * the whole reason that record exists. These are the cheapest tests in the phase and they cover the
 * thing most likely to be quietly wrong: a time printed in the wrong zone reads perfectly and sends
 * somebody to a salon on the wrong afternoon.
 */
class EmailTemplateRendererTest {

    private static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    /** 14:00 in Tbilisi, written as the UTC instant the database stores. */
    private static final Instant STARTS_AT = Instant.parse("2026-09-10T10:00:00Z");

    private static final Instant PREVIOUSLY = Instant.parse("2026-09-09T06:30:00Z");

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    private static MailModel model() {
        return new MailModel(
                "Salon Aria",
                "+995322001122",
                TBILISI,
                "Ana Tsereteli",
                "Haircut",
                "Nino Beridze",
                STARTS_AT,
                PREVIOUSLY,
                "AC7F2K9M",
                "http://localhost:9080/manage/abc.def",
                "Closed for a family emergency");
    }

    /**
     * <strong>The assertion this phase exists for.</strong> The instant is 10:00Z; the Business is on
     * UTC+4; the email must say 14:00. A renderer that used the JVM's zone would pass this on a
     * developer's machine in Tbilisi and fail in CI, which is exactly the bug being ruled out.
     */
    @Test
    void times_are_printed_in_the_business_timezone() {
        RenderedEmail email = renderer.render(NotificationType.BOOKING_CONFIRMATION, model());

        assertThat(email.text()).contains("Thursday 10 September 2026 at 14:00");
        assertThat(email.html()).contains("Thursday 10 September 2026 at 14:00");
        assertThat(email.text()).doesNotContain("10:00");
    }

    @Test
    void the_confirmation_carries_the_code_and_the_manage_link() {
        RenderedEmail email = renderer.render(NotificationType.BOOKING_CONFIRMATION, model());

        assertThat(email.subject()).isEqualTo("Your appointment at Salon Aria is confirmed");
        assertThat(email.text()).contains("AC7F2K9M").contains("http://localhost:9080/manage/abc.def");
        assertThat(email.html()).contains("AC7F2K9M").contains("http://localhost:9080/manage/abc.def");
    }

    @Test
    void the_reschedule_says_where_it_moved_from_and_keeps_the_code() {
        RenderedEmail email = renderer.render(NotificationType.RESCHEDULE, model());

        assertThat(email.subject()).isEqualTo("Your appointment at Salon Aria has moved");
        assertThat(email.text())
                .contains("Wednesday 9 September 2026 at 10:30")
                .contains("Thursday 10 September 2026 at 14:00")
                .contains("has not changed")
                .contains("AC7F2K9M");
    }

    @Test
    void the_cancellation_repeats_the_reason_when_one_was_given() {
        RenderedEmail email = renderer.render(NotificationType.CANCELLATION, model());

        assertThat(email.subject()).isEqualTo("Your appointment at Salon Aria has been cancelled");
        assertThat(email.text()).contains("Closed for a family emergency");
        assertThat(email.html()).contains("Closed for a family emergency");
    }

    /**
     * The conditional block, which is the renderer's only piece of logic. A cancellation with no
     * reason must not render the word "Reason" followed by nothing.
     */
    @Test
    void an_absent_optional_value_takes_its_whole_block_with_it() {
        MailModel withoutReason = new MailModel(
                "Salon Aria", null, TBILISI, "Ana", "Haircut", "Nino", STARTS_AT, null, "AC7F2K9M", "u", null);

        RenderedEmail email = renderer.render(NotificationType.CANCELLATION, withoutReason);

        assertThat(email.text()).doesNotContain("Reason given").doesNotContain("Call us on");
        assertThat(email.html()).doesNotContain("Reason given").doesNotContain("Call us on");
    }

    @Test
    void a_present_optional_value_keeps_its_block() {
        RenderedEmail email = renderer.render(NotificationType.BOOKING_CONFIRMATION, model());

        assertThat(email.html()).contains("Call us on +995322001122");
        assertThat(email.text()).contains("Call us on +995322001122");
    }

    /**
     * A Business name and a Customer name arrive from a form. Unescaped, one containing a tag would
     * be markup in every inbox that renders the HTML part.
     */
    @Test
    void html_escapes_values_and_text_does_not() {
        MailModel hostile = new MailModel(
                "Bob's <script>alert(1)</script> Salon",
                null,
                TBILISI,
                "Ana & Co",
                "Haircut",
                "Nino",
                STARTS_AT,
                null,
                "AC7F2K9M",
                "http://localhost:9080/manage/t",
                null);

        RenderedEmail email = renderer.render(NotificationType.BOOKING_CONFIRMATION, hostile);

        assertThat(email.html()).doesNotContain("<script>").contains("&lt;script&gt;").contains("Ana &amp; Co");
        // The text part has no markup to break, and escaping it would put "&amp;" in front of a
        // reader of a plain-text message.
        assertThat(email.text()).contains("Ana & Co").doesNotContain("&amp;");
    }

    /**
     * The guard for the failure the renderer deliberately does not hide: an unknown key is left
     * standing rather than blanked, so a typo in a template is visible. This asserts none of the
     * nine shipped templates has one.
     */
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void no_template_leaves_a_placeholder_unresolved(NotificationType type) {
        RenderedEmail email = renderer.render(type, model());

        assertThat(email.subject()).doesNotContain("{{");
        assertThat(email.text()).doesNotContain("{{").doesNotContain("}}");
        assertThat(email.html()).doesNotContain("{{").doesNotContain("}}");
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void every_type_renders_a_subject_and_both_bodies(NotificationType type) {
        RenderedEmail email = renderer.render(type, model());

        assertThat(email.subject()).isNotBlank().contains("Salon Aria");
        assertThat(email.text()).isNotBlank().contains("Ana Tsereteli");
        assertThat(email.html()).isNotBlank().contains("<!doctype html>").contains("Ana Tsereteli");
    }

    /** Four types, four distinct subjects — a copy-paste that reused one would be invisible. */
    @Test
    void the_four_subjects_are_all_different() {
        List<String> subjects = List.of(NotificationType.values()).stream()
                .map(type -> renderer.render(type, model()).subject())
                .toList();

        assertThat(subjects).doesNotHaveDuplicates().hasSize(4);
    }
}
