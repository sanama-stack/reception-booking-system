/**
 * The `/employees/*` calls — the person, their service assignments, their Working Schedule and
 * their Time Off.
 *
 * Nothing here takes a business id and nothing ever may (docs/04-api-overview.md §1).
 */

import { api } from '@/lib/api/client';
import type {
  AssignedServices,
  CreateEmployee,
  CreateTimeOff,
  EmployeeBookabilityChange,
  EmployeeDetail,
  EmployeeList,
  EmployeePatch,
  ScheduleSubmission,
  TimeOff,
  TimeOffList,
  WeekSchedule,
} from './types';

export const employeeApi = {
  list: (active?: boolean) =>
    api.get<EmployeeList>('/employees', active === undefined ? undefined : { query: { active } }),
  read: (id: string) => api.get<EmployeeDetail>(`/employees/${id}`),
  create: (employee: CreateEmployee) => api.post<EmployeeDetail>('/employees', employee),
  patch: (id: string, patch: EmployeePatch) => api.patch<EmployeeDetail>(`/employees/${id}`, patch),

  setActive: (id: string, active: boolean) =>
    api.post<EmployeeBookabilityChange>(`/employees/${id}/${active ? 'activate' : 'deactivate'}`),

  /**
   * Replaces the whole service set. The mirror of `PUT /services/{id}/employees` — two views of
   * one table, which is why the server has a single writer for both.
   */
  replaceServices: (id: string, serviceIds: string[]) =>
    api.put<AssignedServices>(`/employees/${id}/services`, { serviceIds }),

  readSchedule: (id: string) => api.get<WeekSchedule>(`/employees/${id}/schedule`),
  /** Replaces the entire week. A day absent from `schedule` is a day this person does not work. */
  replaceSchedule: (id: string, schedule: ScheduleSubmission[]) =>
    api.put<WeekSchedule>(`/employees/${id}/schedule`, { schedule }),

  readTimeOff: (id: string) => api.get<TimeOffList>(`/employees/${id}/time-off`),
  createTimeOff: (id: string, timeOff: CreateTimeOff) =>
    api.post<TimeOff>(`/employees/${id}/time-off`, timeOff),
  deleteTimeOff: (id: string, offId: string) =>
    api.delete<void>(`/employees/${id}/time-off/${offId}`),
};
