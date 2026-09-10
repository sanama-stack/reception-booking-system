package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The refusal the two write tools return for an Appointment this conversation has not proven.
 *
 * <p>One sentence, written once, because the two tools must not be able to phrase it differently:
 * a cancel that said "I don't have that appointment" and a reschedule that said "that appointment
 * belongs to someone else" would together tell an attacker which of the two it was.
 *
 * <p>It says nothing about whether the id exists, and the model is not told either — it is given a
 * refusal and an instruction, so the conversation it produces is "could you give me your
 * confirmation code?" rather than "that appointment is not yours", which would be a claim about a
 * row nobody read.
 */
final class WriteToolAuthorization {

    private WriteToolAuthorization() {}

    static ObjectNode refuse() {
        return ToolResults.error(
                "NOT_AUTHORIZED",
                "I can only change an appointment you have identified in this conversation. Ask the "
                        + "customer for their confirmation code and the phone number they booked with, "
                        + "then call lookup_appointment.");
    }
}
