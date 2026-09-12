'use strict';

/**
 * A fake chat-completions provider, for the E2E flow only.
 *
 * ADR-0011: the E2E's deterministic model lives behind `app.ai.base-url` rather than as a bean, so
 * the application under test is the one that ships and `OpenAiChatModel` stays on the path.
 *
 * It is NOT a queue of canned replies, and it cannot be. A booking conversation has to name a
 * service_id and a starts_at that `make seed` generates fresh on every run, so a reply fixed in
 * advance would name an id that does not exist. This answers from the transcript it is sent —
 * OpenAiChatModel.encode() includes every earlier `role: tool` message — which is the mirror of
 * what backend/tools/receptionist-probe/probe.py does from the other side.
 *
 * Zero dependencies on purpose: it is started by compose from the stock node image with a bind
 * mount, so there is no build step and no lockfile to drift.
 */

const http = require('node:http');

const PORT = Number(process.env.PORT || 8090);
// Fixed identity rather than parsed out of the customer's sentence. This is a fake provider, not a
// natural-language parser, and the E2E asserts these same two constants.
const CUSTOMER_NAME = process.env.FAKE_CUSTOMER_NAME || 'E2E Customer';
const CUSTOMER_PHONE = process.env.FAKE_CUSTOMER_PHONE || '+995555000111';

/**
 * Two days out, not tomorrow — T44. The nearest bookable slot falls inside the 24-hour Cancellation
 * Window, and the E2E goes on to follow the Manage Link and cancel, which the window would refuse.
 * The demo script dead-ended on exactly this.
 *
 * Computed in UTC while the Business keeps its own timezone, so the distance to the chosen slot is
 * not exactly two days. Measured against Salon Aria (`Asia/Tbilisi`, opening 10:00): **42.9 hours**.
 *
 * The worst case is a tenant far east of UTC opening very early — at `+14:00`, a 00:00 slot on
 * `date_from` is only 10 hours after a 23:59 UTC start. Two days is therefore *not* "safe in any
 * zone", which an earlier version of this comment claimed; it is safe given a business that does
 * not open in the small hours. Both seeded tenants open at 08:00 or later, at `+04:00` and
 * `+01:00`. Raise this to 3 before pointing the E2E at a tenant that does neither.
 */
const SEARCH_FROM_DAYS = Number(process.env.FAKE_SEARCH_FROM_DAYS || 2);
const SEARCH_WINDOW_DAYS = Number(process.env.FAKE_SEARCH_WINDOW_DAYS || 11);

function isoDate(offsetDays) {
    const day = new Date(Date.now() + offsetDays * 86400000);
    return day.toISOString().slice(0, 10);
}

/**
 * Every tool result so far, by tool name.
 *
 * A `role: tool` message carries only a tool_call_id, so the name comes from the assistant message
 * that requested it. Later results win, which is what makes a retried call behave.
 */
function toolResults(messages) {
    const names = new Map();
    const results = new Map();

    for (const message of messages) {
        for (const call of message.tool_calls || []) {
            names.set(call.id, call.function && call.function.name);
        }
        if (message.role === 'tool' && names.has(message.tool_call_id)) {
            try {
                results.set(names.get(message.tool_call_id), JSON.parse(message.content));
            } catch {
                results.set(names.get(message.tool_call_id), { unparseable: message.content });
            }
        }
    }
    return results;
}

/** The service the customer asked for, by name mentioned in anything they typed; else the first. */
function chooseService(services, messages) {
    const said = messages
        .filter((message) => message.role === 'user')
        .map((message) => message.content || '')
        .join(' ')
        .toLowerCase();

    return services.find((service) => said.includes((service.name || '').toLowerCase())) || services[0];
}

function textReply(content) {
    return { role: 'assistant', content };
}

function callReply(name, args) {
    return {
        role: 'assistant',
        content: null,
        tool_calls: [
            {
                // Stable and unique within a conversation; the loop only ever echoes it back.
                id: `call_${name}_${Math.random().toString(36).slice(2, 10)}`,
                type: 'function',
                function: { name, arguments: JSON.stringify(args) },
            },
        ],
    };
}

/**
 * The whole policy. Four states, decided by which tool results are already in the transcript rather
 * than by a turn counter — so a retry, an extra user turn or a tool error cannot slide it out of
 * step.
 */
function decide(messages) {
    const seen = toolResults(messages);

    if (!seen.has('get_services')) {
        return callReply('get_services', {});
    }

    const services = (seen.get('get_services') || {}).services || [];
    if (services.length === 0) {
        return textReply('This business has no bookable services, so there is nothing I can book.');
    }
    const service = chooseService(services, messages);

    if (!seen.has('find_available_slots')) {
        return callReply('find_available_slots', {
            service_id: service.service_id,
            date_from: isoDate(SEARCH_FROM_DAYS),
            date_to: isoDate(SEARCH_FROM_DAYS + SEARCH_WINDOW_DAYS),
        });
    }

    const found = seen.get('find_available_slots') || {};
    const slots = found.slots || [];
    if (slots.length === 0) {
        // A readable failure rather than a crash: the E2E fails on a missing confirmation card and
        // the reason is sitting in the transcript.
        return textReply(
            `I could not find a free ${service.name} between ${found.searched_from} and ` +
                `${found.searched_to} (${found.empty_reason || 'no reason given'}).`
        );
    }
    const slot = slots[0];

    if (!seen.has('create_appointment')) {
        return callReply('create_appointment', {
            service_id: service.service_id,
            employee_id: slot.employee_id,
            // Verbatim, offset included. The tool requires exactly what the slot returned.
            starts_at: slot.starts_at,
            customer_name: CUSTOMER_NAME,
            customer_phone: CUSTOMER_PHONE,
        });
    }

    const booked = seen.get('create_appointment') || {};
    if (booked.error) {
        return textReply(`I could not book that after all: ${booked.error.message || booked.error.code}.`);
    }

    return textReply(
        `You're booked for ${booked.service_name} with ${booked.employee_name} at ` +
            `${booked.starts_at}. Your confirmation code is ${booked.confirmation_code}.`
    );
}

const server = http.createServer((request, response) => {
    if (request.method !== 'POST' || !request.url.endsWith('/chat/completions')) {
        response.writeHead(404, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: { message: `no route for ${request.method} ${request.url}` } }));
        return;
    }

    let body = '';
    request.on('data', (chunk) => {
        body += chunk;
    });
    request.on('end', () => {
        let parsed;
        try {
            parsed = JSON.parse(body);
        } catch (e) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: { message: 'request body is not JSON' } }));
            return;
        }

        const message = decide(parsed.messages || []);
        const called = (message.tool_calls || []).map((call) => call.function.name).join(',');
        console.log(`[fake-provider] ${(parsed.messages || []).length} messages -> ${called || 'text'}`);

        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(
            JSON.stringify({
                id: 'chatcmpl-fake',
                object: 'chat.completion',
                model: parsed.model || 'fake',
                choices: [{ index: 0, message, finish_reason: called ? 'tool_calls' : 'stop' }],
                // Real numbers so the cost ledger has something to record. Not the real token count
                // of anything; nothing in the E2E asserts spend.
                usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 },
            })
        );
    });
});

server.listen(PORT, () => console.log(`[fake-provider] listening on ${PORT}`));
