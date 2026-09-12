'use strict';

/**
 * The fake provider, driven the way OpenAiChatModel drives it.
 *
 * A double that has only ever been seen to answer is indistinguishable from one that answers
 * wrongly, so this asserts the transcript-reading property directly (case 6) rather than only
 * walking the happy path: fed a conversation where get_services has already returned, it must move
 * on rather than ask again. A turn counter would pass cases 1-5 and fail that one.
 *
 * No dependencies and no runner: `node selftest.js`, exit code 0 or 1.
 */

const { spawn } = require('node:child_process');
const path = require('node:path');

const PORT = 8731;
const URL = `http://127.0.0.1:${PORT}/v1/chat/completions`;

const SERVICE_ID = '0f8b2c3d-1111-4444-8888-aaaabbbbcccc';
const EMPLOYEE_ID = '77778888-2222-4444-8888-ccccddddeeee';
const SLOT = '2026-09-16T14:00:00+04:00';

const SERVICES = {
    services: [
        { service_id: 'colour-id', name: 'Colour', duration_minutes: 150, price: '120.00', currency: 'GEL' },
        { service_id: SERVICE_ID, name: 'Haircut', duration_minutes: 45, price: '40.00', currency: 'GEL' },
    ],
};
const SLOTS = {
    searched_from: '2026-09-14',
    searched_to: '2026-09-25',
    truncated: false,
    slots: [{ starts_at: SLOT, ends_at: '2026-09-16T14:45:00+04:00', employee_id: EMPLOYEE_ID, employee_name: 'Nino Beridze' }],
};
const BOOKED = {
    appointment_id: 'appt-1',
    confirmation_code: 'RC-7Q2X',
    starts_at: SLOT,
    service_name: 'Haircut',
    employee_name: 'Nino Beridze',
};

const failures = [];
function check(name, condition, detail) {
    if (condition) {
        console.log(`  ok   ${name}`);
    } else {
        console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
        failures.push(name);
    }
}

async function ask(messages) {
    const response = await fetch(URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'gpt-4o-mini', messages, tools: [], tool_choice: 'auto' }),
    });
    return { status: response.status, body: await response.json() };
}

const assistant = (calls) => ({ role: 'assistant', content: null, tool_calls: calls });
const toolCall = (id, name, args = {}) => ({ id, type: 'function', function: { name, arguments: JSON.stringify(args) } });
const toolMessage = (id, payload) => ({ role: 'tool', tool_call_id: id, content: JSON.stringify(payload) });
const called = (body) => ((body.choices[0].message.tool_calls || [])[0] || {}).function;

async function main() {
    // 1-3: the whole booking, with the tools standing in.
    let messages = [
        { role: 'system', content: 'You are the receptionist.' },
        { role: 'user', content: 'Hi, I would like a Haircut please.' },
    ];
    const order = [];
    let final = null;

    for (let round = 0; round < 8 && final === null; round++) {
        const { body } = await ask(messages);
        const message = body.choices[0].message;
        if (!message.tool_calls) {
            final = message.content;
            check('finish_reason is stop on a text reply', body.choices[0].finish_reason === 'stop');
            break;
        }
        check('finish_reason is tool_calls on a call', body.choices[0].finish_reason === 'tool_calls');
        messages.push(message);
        for (const call of message.tool_calls) {
            const name = call.function.name;
            const args = JSON.parse(call.function.arguments);
            order.push(name);
            if (name === 'find_available_slots') {
                check('find_available_slots gets the id of the service the customer named', args.service_id === SERVICE_ID, args.service_id);
                check('find_available_slots is bounded on both sides', Boolean(args.date_from && args.date_to));
                check('the search starts at least two days out (T44)', args.date_from >= new Date(Date.now() + 86400000).toISOString().slice(0, 10), args.date_from);
            }
            if (name === 'create_appointment') {
                check('starts_at is copied verbatim, offset included', args.starts_at === SLOT, args.starts_at);
                check('employee_id comes from the chosen slot', args.employee_id === EMPLOYEE_ID, args.employee_id);
                check('the customer has a name and a phone number', Boolean(args.customer_name && args.customer_phone));
            }
            const payload = name === 'get_services' ? SERVICES : name === 'find_available_slots' ? SLOTS : BOOKED;
            messages.push(toolMessage(call.id, payload));
        }
    }

    check('the three tools are called once each, in order', order.join(',') === 'get_services,find_available_slots,create_appointment', order.join(','));
    check('the reply quotes the Confirmation Code the tool returned', typeof final === 'string' && final.includes('RC-7Q2X'), final);

    // 4: an empty grid is a sentence, not a crash.
    const empty = await ask([
        { role: 'user', content: 'A Haircut please.' },
        assistant([toolCall('c1', 'get_services')]),
        toolMessage('c1', SERVICES),
        assistant([toolCall('c2', 'find_available_slots')]),
        toolMessage('c2', { searched_from: '2026-09-14', searched_to: '2026-09-25', slots: [], empty_reason: 'FULLY_BOOKED' }),
    ]);
    check('an empty grid names the engine\'s own reason', (empty.body.choices[0].message.content || '').includes('FULLY_BOOKED'));

    // 5: a tool error is reported, not celebrated.
    const failed = await ask([
        { role: 'user', content: 'A Haircut please.' },
        assistant([toolCall('c1', 'get_services')]),
        toolMessage('c1', SERVICES),
        assistant([toolCall('c2', 'find_available_slots')]),
        toolMessage('c2', SLOTS),
        assistant([toolCall('c3', 'create_appointment')]),
        toolMessage('c3', { error: { code: 'SLOT_TAKEN', message: 'that slot has gone' } }),
    ]);
    const afterError = failed.body.choices[0].message.content || '';
    check('a failed booking is reported as a failure', afterError.includes('could not book'), afterError);
    check('a failed booking invents no Confirmation Code', !/RC-/.test(afterError), afterError);

    // 6: THE property. Reads the transcript; does not count turns.
    const midway = await ask([
        { role: 'user', content: 'A Haircut please.' },
        assistant([toolCall('c1', 'get_services')]),
        toolMessage('c1', SERVICES),
    ]);
    check('given an existing get_services result, it moves on rather than asking again', (called(midway.body) || {}).name === 'find_available_slots', JSON.stringify(called(midway.body)));

    // The same transcript with extra chatter in front: a counter would be one off, this must not be.
    const padded = await ask([
        { role: 'user', content: 'Hello?' },
        { role: 'assistant', content: 'Hello! How can I help?' },
        { role: 'user', content: 'A Haircut please.' },
        assistant([toolCall('c1', 'get_services')]),
        toolMessage('c1', SERVICES),
    ]);
    check('extra conversation before the tools does not shift the state', (called(padded.body) || {}).name === 'find_available_slots', JSON.stringify(called(padded.body)));

    // 7: the edges of the HTTP surface.
    const notFound = await fetch(`http://127.0.0.1:${PORT}/v1/models`, { method: 'GET' });
    check('an unknown route is a 404, not a 200 with an empty body', notFound.status === 404, String(notFound.status));
    const badBody = await fetch(URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: 'not json' });
    check('a body that is not JSON is a 400', badBody.status === 400, String(badBody.status));
}

const server = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...process.env, PORT: String(PORT) },
    stdio: ['ignore', 'ignore', 'inherit'],
});

const ready = async () => {
    for (let attempt = 0; attempt < 50; attempt++) {
        try {
            await fetch(URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"messages":[]}' });
            return true;
        } catch {
            await new Promise((resolve) => setTimeout(resolve, 100));
        }
    }
    return false;
};

(async () => {
    if (!(await ready())) {
        console.error('the fake provider never came up');
        process.exitCode = 1;
        return;
    }
    console.log('fake-provider selftest');
    try {
        await main();
    } catch (e) {
        console.log(`  FAIL threw — ${e.message}`);
        failures.push('threw');
    }
    console.log(failures.length === 0 ? '\nall checks passed' : `\n${failures.length} check(s) failed`);
    process.exitCode = failures.length === 0 ? 0 : 1;
})().finally(() => server.kill());
