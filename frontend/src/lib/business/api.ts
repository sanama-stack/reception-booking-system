/**
 * The `/business/*` calls, named once so no screen writes a path string.
 *
 * Nothing here takes a business id and nothing ever may: the tenant is a claim in the access
 * cookie, and an endpoint that accepted one would be a defect on the server before it was one here
 * (docs/04-api-overview.md §1).
 */

import { api } from '@/lib/api/client';
import type {
  BusinessPatch,
  BusinessProfile,
  ClosureList,
  CreateClosure,
  CreateFaq,
  CreatedClosure,
  Faq,
  FaqList,
  FaqPatch,
  HoursInterval,
  Onboarding,
  WeekHours,
} from './types';

export const businessApi = {
  read: () => api.get<BusinessProfile>('/business'),
  patch: (patch: BusinessPatch) => api.patch<BusinessProfile>('/business', patch),

  readHours: () => api.get<WeekHours>('/business/hours'),
  /** Replaces the entire week. A day absent from `hours` is closed. */
  replaceHours: (hours: HoursInterval[]) => api.put<WeekHours>('/business/hours', { hours }),

  readClosures: () => api.get<ClosureList>('/business/closures'),
  createClosure: (closure: CreateClosure) =>
    api.post<CreatedClosure>('/business/closures', closure),
  deleteClosure: (id: string) => api.delete<void>(`/business/closures/${id}`),

  readFaqs: () => api.get<FaqList>('/business/faqs'),
  createFaq: (faq: CreateFaq) => api.post<Faq>('/business/faqs', faq),
  patchFaq: (id: string, patch: FaqPatch) => api.patch<Faq>(`/business/faqs/${id}`, patch),
  deleteFaq: (id: string) => api.delete<void>(`/business/faqs/${id}`),

  readOnboarding: () => api.get<Onboarding>('/business/onboarding'),
};
