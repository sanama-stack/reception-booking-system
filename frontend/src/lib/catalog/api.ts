/**
 * The `/services/*` calls, named once so no screen writes a path string.
 *
 * Nothing here takes a business id and nothing ever may: the tenant is a claim in the access
 * cookie (docs/04-api-overview.md §1).
 */

import { api } from '@/lib/api/client';
import type {
  AssignedEmployees,
  CreateService,
  ServiceBookabilityChange,
  ServiceDetail,
  ServiceList,
  ServicePatch,
} from './types';

export const serviceApi = {
  /** `active` omitted lists everything, which is what a management screen wants. */
  list: (active?: boolean) =>
    api.get<ServiceList>('/services', active === undefined ? undefined : { query: { active } }),
  read: (id: string) => api.get<ServiceDetail>(`/services/${id}`),
  create: (service: CreateService) => api.post<ServiceDetail>('/services', service),
  patch: (id: string, patch: ServicePatch) => api.patch<ServiceDetail>(`/services/${id}`, patch),

  setActive: (id: string, active: boolean) =>
    api.post<ServiceBookabilityChange>(`/services/${id}/${active ? 'activate' : 'deactivate'}`),

  /** Refused with `409 SERVICE_IN_USE` once the service has ever been booked. */
  delete: (id: string) => api.delete<void>(`/services/${id}`),

  /**
   * Replaces the whole eligible-employee set. An empty list is legitimate and means nobody can
   * perform this service yet; a submitted id that resolves to nothing is a `404`, not a silently
   * shorter set.
   */
  replaceEmployees: (id: string, employeeIds: string[]) =>
    api.put<AssignedEmployees>(`/services/${id}/employees`, { employeeIds }),
};
