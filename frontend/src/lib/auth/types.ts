/** The shape `/auth/register`, `/auth/login`, `/auth/refresh` and `/auth/me` all return. */
export interface SessionUser {
  id: string;
  email: string;
  fullName: string;
}

export interface SessionBusiness {
  id: string;
  name: string;
  slug: string;
  /** IANA zone id. Every datetime in the app is rendered in this zone (ADR-0003). */
  timezone: string;
  currency: string;
}

export interface Session {
  user: SessionUser;
  business: SessionBusiness;
  role: 'OWNER' | 'ADMIN' | 'STAFF';
}
