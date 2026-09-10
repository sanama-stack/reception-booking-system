import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { cache } from 'react';
import { ErrorState } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { publicApi, type PublicBusiness, type PublicService } from '@/lib/public';
import { toBusinessDate } from '@/lib/time';
import { BusinessPanel } from './business-panel';
import { ClassicFlow } from './classic-flow';
import { NotAcceptingBookings } from './not-accepting-bookings';

/**
 * A Business's public booking page.
 *
 * **Server-rendered, and the part that is rendered on the server is the part that is worth
 * indexing and worth showing before JavaScript arrives**: who this business is, where they are,
 * when they are open, and what they charge. The Classic Flow below it is a client component
 * because it is a conversation with the availability engine — but a visitor on a slow connection
 * sees a real page describing a real business first, rather than a spinner.
 *
 * The two reads run anonymously. Nothing forwards the visitor's cookies into a server-side fetch,
 * so an owner opening their own booking page is served exactly what a stranger is served — which
 * is the arrangement in which a response-minimisation mistake is visible to the person who could
 * fix it, rather than hidden from them.
 */

/**
 * Dynamic, because this page has no version that could be cached and still be true: availability
 * moves minute by minute, and the day strip is anchored to today in the *business's* timezone,
 * which the server resolves from its own clock. The reads already say `no-store`, which is enough
 * on its own; saying it here as well makes the reason legible instead of implied.
 */
export const dynamic = 'force-dynamic';

interface PageProps {
  params: Promise<{ slug: string }>;
}

/**
 * Read once, used by both `generateMetadata` and the page itself.
 *
 * Wrapped in React's `cache` rather than relying on Next's fetch-level request memoisation, which
 * is documented for `fetch` but interacts with `cache: 'no-store'` in ways this page should not
 * have to reason about. `cache` memoises *this function* for the life of the request, so the two
 * entry points below share one pair of round trips by construction.
 *
 * Returning the `ApiError` instead of throwing it keeps the failure handling in one place. Thrown,
 * it would have to be caught twice — and an uncaught one inside `generateMetadata` fails the whole
 * render, so a business whose backend is briefly down would produce a stack trace rather than the
 * page that explains itself.
 */
const read = cache(async (slug: string): Promise<[PublicBusiness, PublicService[]] | ApiError> => {
  try {
    return await publicApi.page(slug);
  } catch (cause) {
    if (cause instanceof ApiError) return cause;
    throw cause;
  }
});

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { slug } = await params;
  const result = await read(slug);
  if (result instanceof ApiError) return { title: 'Booking' };

  const [business] = result;
  return {
    title: `Book with ${business.name}`,
    description:
      business.description ??
      `Book an appointment with ${business.name}${business.city ? ` in ${business.city}` : ''}.`,
    // A booking page is for the person holding the link, not for a crawler building an index of
    // every business in the system. Phase 11 owns whatever the marketing surface wants here.
    robots: { index: false, follow: false },
  };
}

export default async function BookingPage({ params }: PageProps) {
  const { slug } = await params;
  const result = await read(slug);

  if (result instanceof ApiError) {
    // A `404` is the slug filter saying there is no booking page at this address, and it is the
    // one failure with a designed page of its own. Everything else — the backend being down, a
    // rate limit, a `500` — is a fault rather than a wrong address, and saying "no such business"
    // for it would tell a visitor their link is broken when it is not.
    if (result.status === 404) notFound();
    return (
      <main className="mx-auto flex min-h-dvh max-w-lg flex-col justify-center px-4 py-16">
        <ErrorState error={result} />
        <p className="text-ink-muted mt-4 text-sm">
          This is a problem at our end, not with your link. Trying again in a few minutes is worth
          more than reloading now.
        </p>
      </main>
    );
  }

  const [business, services] = result;
  const today = toBusinessDate(new Date(), business.timezone);

  return (
    <main className="mx-auto w-full max-w-5xl px-4 py-8 sm:px-6 sm:py-12">
      <header className="mb-8">
        <h1 className="text-ink text-2xl font-semibold tracking-tight sm:text-3xl">
          {business.name}
        </h1>
        {business.description && (
          <p className="text-ink-muted mt-2 max-w-2xl text-sm leading-relaxed sm:text-base">
            {business.description}
          </p>
        )}
        <Location business={business} />
      </header>

      {/*
        Two columns: the Classic Flow, and the Receptionist above the reference material. Phase 08
        reserved the right-hand column and phase 09 filled it, which is why the layout was laid out
        before there was anything to put in it — the Classic Flow is the permanent fallback for
        every AI failure mode (docs/05-ai-architecture.md §8), so it has to be a first-class column
        beside the chat rather than something the chat pushes off-screen.

        `24rem` rather than the `19rem` phase 08 reserved. 304 px is enough for opening hours and
        was never enough for a transcript: at that width a message bubble wraps every four or five
        words and the confirmation card inside it is unreadable. The 80 px comes out of the Classic
        Flow's column, which drops to roughly 560 px and needs about 440 for its widest row — the
        slot grid — so the trade costs it nothing that shows.

        One column below `lg`, flow first. A visitor at 360 px came to book, not to read the
        opening hours, so the hours and the policy follow the flow rather than preceding it.
      */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_24rem] lg:items-start lg:gap-8">
        {/*
          The target of the Receptionist's permanent "or book the classic way" link. `scroll-mt-6`
          is what keeps the heading off the very top edge when the anchor lands, and the id lives on
          a wrapper rather than inside `ClassicFlow` so that the fallback still has somewhere to go
          when this business has no services and the other branch renders instead.
        */}
        <div id="classic-flow" className="scroll-mt-6">
          {services.length === 0 ? (
            <NotAcceptingBookings business={business} />
          ) : (
            <ClassicFlow slug={slug} business={business} services={services} today={today} />
          )}
        </div>
        <BusinessPanel business={business} slug={slug} />
      </div>
    </main>
  );
}

/** Address and contact details, on one line where they fit and stacked where they do not. */
function Location({ business }: { business: PublicBusiness }) {
  const address = [business.addressLine, business.city].filter(Boolean).join(', ');
  if (!address && !business.phone && !business.website) return null;

  return (
    <div className="text-ink-muted mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
      {address && <span>{address}</span>}
      {business.phone && (
        <a className="hover:text-ink hover:underline" href={`tel:${business.phone}`}>
          {business.phone}
        </a>
      )}
      {business.website && (
        <a
          className="hover:text-ink hover:underline"
          href={business.website}
          // A Business's own website is a link this application does not vouch for. `noopener`
          // is what stops the opened page reaching back through `window.opener`, and `noreferrer`
          // keeps the booking URL — which is not secret but is nobody else's business — out of
          // its referrer log.
          target="_blank"
          rel="noopener noreferrer nofollow"
        >
          Website
        </a>
      )}
    </div>
  );
}
