import { redirect } from 'next/navigation';

/**
 * `/settings` is the section, not a screen. Anyone arriving here — a bookmark, the sidebar link,
 * a typed URL — wants the first page of it.
 */
export default function SettingsIndexPage() {
  redirect('/settings/profile');
}
