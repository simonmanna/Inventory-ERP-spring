import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/**
 * Project management (ADR-S062) client: projects workspace, tasks, milestones, updates, chatter,
 * timesheets / timer and reports. Every response is a plain object or array (no envelope) except
 * the task list, which is `{ data, total }`.
 */

export type Num = number | string;
export type ProjectStatus = 'draft' | 'active' | 'on_hold' | 'completed' | 'closed' | 'cancelled';
export type UpdateStatus = 'on_track' | 'at_risk' | 'off_track' | 'on_hold' | 'done';
export type Priority = 'low' | 'normal' | 'high' | 'urgent';
export type KanbanState = 'normal' | 'blocked' | 'ready';
export type StageKind = 'open' | 'done' | 'cancelled';
export type MemberRole = 'manager' | 'member' | 'viewer';

export interface UserRef { id: string; name: string; email?: string }
export interface TagRef { id: string; name: string; color: number }

export interface ProjectCard {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  status: ProjectStatus;
  privacy: 'internal' | 'members';
  color: number;
  managerId?: string | null;
  managerName?: string | null;
  customerId?: string | null;
  customerName?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  plannedHours: Num;
  effectiveHours: Num;
  lastUpdateStatus?: UpdateStatus | null;
  isTemplate: boolean;
  allowTimesheets: boolean;
  isFavorite: boolean;
  taskCount: number;
  openTaskCount: number;
  overdueTaskCount: number;
  myOpenTaskCount: number;
  memberCount: number;
  progress: number;
  nextMilestone?: { id: string; name: string; deadline?: string | null } | null;
}

export interface Stage {
  id: string;
  projectId: string;
  name: string;
  sequence: number;
  kind: StageKind;
  folded: boolean;
}

export interface Member { userId: string; role: MemberRole; email?: string; name: string }

export interface Milestone {
  id: string;
  projectId: string;
  name: string;
  description?: string | null;
  deadline?: string | null;
  isReached: boolean;
  reachedAt?: string | null;
  taskCount: number;
  doneCount: number;
  late: boolean;
}

export interface Workspace extends Omit<ProjectCard, 'taskCount' | 'openTaskCount' | 'overdueTaskCount' | 'myOpenTaskCount' | 'memberCount' | 'progress' | 'nextMilestone' | 'effectiveHours'> {
  allowTimesheets: boolean;
  allowMilestones: boolean;
  allowDependencies: boolean;
  allowRecurring: boolean;
  contractValue?: Num | null;
  salesOrderId?: string | null;
  stages: Stage[];
  members: Member[];
  milestones: Milestone[];
  myRole?: MemberRole | null;
  canEditTasks: boolean;
  canManage: boolean;
  canSeeCosts: boolean;
  wbs: Array<{ id: string; code: string; name: string }>;
  sites: Array<{ id: string; code: string; name: string }>;
  salesOrder?: { label?: string; path?: string } | null;
}

export interface Task {
  id: string;
  projectId?: string | null;
  projectCode?: string | null;
  projectName?: string | null;
  number: string;
  title: string;
  description?: string | null;
  stageId?: string | null;
  stageName?: string | null;
  stageKind?: StageKind | null;
  parentId?: string | null;
  milestoneId?: string | null;
  milestoneName?: string | null;
  partnerId?: string | null;
  partnerName?: string | null;
  wbsId?: string | null;
  siteId?: string | null;
  priority: Priority;
  kanbanState: KanbanState;
  blockedReason?: string | null;
  plannedStart?: string | null;
  deadline?: string | null;
  plannedHours: Num;
  effectiveHours: Num;
  sequence: number;
  color: number;
  isClosed: boolean;
  closedAt?: string | null;
  recurrence?: 'daily' | 'weekly' | 'monthly' | 'yearly' | null;
  recurrenceInterval: number;
  recurrenceUntil?: string | null;
  sourceResType?: string | null;
  sourceResId?: string | null;
  checklistDone: number;
  checklistTotal: number;
  subtaskCount: number;
  subtaskDone: number;
  blockedByOpen: number;
  assignees: UserRef[];
  tags: TagRef[];
  progress: number | null;
  overdue: boolean;
  createdAt: string;
}

export interface TaskLink { id: string; number: string; title: string; isClosed?: boolean; deadline?: string | null }
export interface ChecklistItem { id: string; title: string; isDone: boolean; sequence: number }

export interface TaskDetail extends Task {
  subtasks: Task[];
  blockedBy: TaskLink[];
  blocking: TaskLink[];
  checklist: ChecklistItem[];
  followers: UserRef[];
  isFollowing: boolean;
  source?: { label?: string; path?: string } | null;
  parent?: { id: string; number: string; title: string } | null;
  myHours: Num;
  stages: Stage[];
  canEdit: boolean;
  canDelete: boolean;
}

export interface Message {
  id: string;
  projectId?: string | null;
  taskId?: string | null;
  taskNumber?: string | null;
  taskTitle?: string | null;
  kind: 'comment' | 'note' | 'system';
  body: string;
  authorId?: string | null;
  authorName?: string | null;
  createdAt: string;
}

export interface ProjectUpdate {
  id: string;
  status: UpdateStatus;
  progress: number;
  summary?: string | null;
  date: string;
  authorId?: string | null;
  authorName?: string | null;
  createdAt: string;
}

export interface Overview {
  projectId: string;
  tasks: { total: number; open: number; closed: number; overdue: number; blocked: number; unassigned: number; progress: number };
  byStage: Array<{ id: string; name: string; kind: StageKind; count: number }>;
  byAssignee: Array<{ userId: string; name: string; open: number; closed: number; overdue: number; plannedHours: Num }>;
  hours: { planned: Num; taskPlanned: Num; effective: Num; billable: Num; remaining: Num; usedPercent: Num | null };
  milestones: Milestone[];
  lastUpdate: ProjectUpdate | null;
  upcoming: Task[];
  links: {
    salesOrder?: { label?: string; path?: string } | null;
    deal?: { label?: string; path?: string } | null;
    purchaseRequests: number;
    linkedTasks: Array<{ resType: string; count: number }>;
  };
  financials?: Record<string, Num | string | null>;
}

export interface TimesheetLine {
  id: string;
  projectId: string;
  projectCode: string;
  projectName: string;
  taskId?: string | null;
  taskNumber?: string | null;
  taskTitle?: string | null;
  userId: string;
  userName?: string | null;
  date: string;
  hours: Num;
  description?: string | null;
  isBillable: boolean;
  hrStatus?: string | null;
  locked: boolean;
}

export interface WeekRow {
  projectId: string;
  projectCode: string;
  projectName: string;
  taskId?: string | null;
  taskNumber?: string | null;
  taskTitle?: string | null;
  hours: Num[];
  total: Num;
}

export interface Week {
  start: string;
  days: string[];
  userId: string;
  userName?: string | null;
  rows: WeekRow[];
  totals: Num[];
  total: Num;
}

export interface Timer {
  running: boolean;
  taskId?: string;
  taskNumber?: string;
  taskTitle?: string;
  projectId?: string;
  projectCode?: string;
  projectName?: string;
  startedAt?: string;
  elapsedSeconds?: number;
}

export interface Dashboard {
  myTasks: { open: number; overdue: number; dueToday: number; dueThisWeek: number; doneThisWeek: number; blocked: number };
  weekHours: Num;
  hoursByDay: Array<{ date: string; hours: Num }>;
  projectsAtRisk: Array<{ id: string; code: string; name: string; lastUpdateStatus?: UpdateStatus | null; endDate?: string | null; overdue: number }>;
  focus: Task[];
  today: string;
}

export interface TaskGroupRow {
  key: string | null;
  label: string | null;
  total: number;
  open: number;
  closed: number;
  overdue: number;
  closedLate: number;
  plannedHours: Num;
  effectiveHours: Num;
  avgDaysToClose: Num | null;
}

export interface TimeGroupRow { key: string | null; label: string | null; hours: Num; billableHours: Num; lines: number; people: number }

export interface WorkloadRow { userId: string; name: string; open: number; overdue: number; dueThisWeek: number; remainingHours: Num; projects: number }

export interface Gantt {
  project: { id: string; code: string; name: string; startDate?: string | null; endDate?: string | null };
  tasks: Task[];
  dependencies: Array<{ taskId: string; dependsOnId: string }>;
  milestones: Array<{ id: string; name: string; deadline?: string | null; isReached: boolean }>;
}

export interface TaskFilters {
  projectId?: string;
  stageId?: string;
  assigneeId?: string;
  tagId?: string;
  milestoneId?: string;
  search?: string;
  state?: 'open' | 'closed' | 'all';
  priority?: string;
  overdue?: boolean;
  personal?: boolean;
  topLevel?: boolean;
  parentId?: string;
  deadlineTo?: string;
  limit?: number;
}

/** Drop empty strings / undefined so they never reach the query string. */
function clean<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '' && v !== null)) as Partial<T>;
}

export const num = (v: Num | null | undefined): number => (v == null || v === '' ? 0 : Number(v));

const KEY = ['pm'] as const;

// ---- projects ---------------------------------------------------------------------------------------------------------

export function useProjectBoard(params: { filter?: string; status?: string; search?: string; templates?: boolean }) {
  return useQuery<ProjectCard[]>({
    queryKey: [...KEY, 'board', params],
    queryFn: async () => (await api.get('/projects/workspace', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useWorkspace(id: string | undefined) {
  return useQuery<Workspace>({
    queryKey: [...KEY, 'workspace', id],
    enabled: !!id,
    queryFn: async () => (await api.get(`/projects/${id}/workspace`)).data,
  });
}

export function useOverview(id: string | undefined) {
  return useQuery<Overview>({
    queryKey: [...KEY, 'overview', id],
    enabled: !!id,
    queryFn: async () => (await api.get(`/projects/${id}/overview`)).data,
  });
}

export function useAssignableUsers() {
  return useQuery<UserRef[]>({
    queryKey: [...KEY, 'users'],
    staleTime: 5 * 60_000,
    queryFn: async () => (await api.get('/projects/assignable-users')).data,
  });
}

export function useTags() {
  return useQuery<Array<TagRef & { taskCount: number }>>({
    queryKey: [...KEY, 'tags'],
    staleTime: 60_000,
    queryFn: async () => (await api.get('/project-tags')).data,
  });
}

export function useProjectMessages(id: string | undefined, includeTasks: boolean) {
  return useQuery<Message[]>({
    queryKey: [...KEY, 'messages', id, includeTasks],
    enabled: !!id,
    queryFn: async () => (await api.get(`/projects/${id}/messages`, { params: { includeTasks } })).data,
  });
}

export function useProjectUpdates(id: string | undefined) {
  return useQuery<ProjectUpdate[]>({
    queryKey: [...KEY, 'updates', id],
    enabled: !!id,
    queryFn: async () => (await api.get(`/projects/${id}/updates`)).data,
  });
}

// ---- tasks ------------------------------------------------------------------------------------------------------------

export function useTasks(filters: TaskFilters, enabled = true) {
  return useQuery<{ data: Task[]; total: number }>({
    queryKey: [...KEY, 'tasks', filters],
    enabled,
    queryFn: async () => (await api.get('/project-tasks', { params: clean(filters) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useTask(id: string | null | undefined) {
  return useQuery<TaskDetail>({
    queryKey: [...KEY, 'task', id],
    enabled: !!id,
    queryFn: async () => (await api.get(`/project-tasks/${id}`)).data,
  });
}

export function useTaskMessages(id: string | null | undefined) {
  return useQuery<Message[]>({
    queryKey: [...KEY, 'task-messages', id],
    enabled: !!id,
    queryFn: async () => (await api.get(`/project-tasks/${id}/messages`)).data,
  });
}

// ---- time -------------------------------------------------------------------------------------------------------------

export function useWeek(start: string | undefined, userId?: string) {
  return useQuery<Week>({
    queryKey: [...KEY, 'week', start, userId],
    queryFn: async () => (await api.get('/project-timesheets/week', { params: clean({ start, userId }) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useTimesheetLines(params: { projectId?: string; taskId?: string; userId?: string; from?: string; to?: string }, enabled = true) {
  return useQuery<TimesheetLine[]>({
    queryKey: [...KEY, 'lines', params],
    enabled,
    queryFn: async () => (await api.get('/project-timesheets', { params: clean(params) })).data,
  });
}

export function useTimer(enabled = true) {
  return useQuery<Timer>({
    queryKey: [...KEY, 'timer'],
    enabled,
    refetchOnWindowFocus: true,
    queryFn: async () => (await api.get('/project-timesheets/timer')).data,
  });
}

// ---- reports ----------------------------------------------------------------------------------------------------------

export function useDashboard() {
  return useQuery<Dashboard>({
    queryKey: [...KEY, 'dashboard'],
    queryFn: async () => (await api.get('/project-reports/dashboard')).data,
  });
}

export function useTaskAnalysis(params: { groupBy: string; projectId?: string; from?: string; to?: string }) {
  return useQuery<TaskGroupRow[]>({
    queryKey: [...KEY, 'report-tasks', params],
    queryFn: async () => (await api.get('/project-reports/tasks', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useTimeAnalysis(params: { groupBy: string; projectId?: string; userId?: string; from?: string; to?: string }) {
  return useQuery<TimeGroupRow[]>({
    queryKey: [...KEY, 'report-time', params],
    queryFn: async () => (await api.get('/project-reports/timesheets', { params: clean(params) })).data,
    placeholderData: keepPreviousData,
  });
}

export function useWorkload(projectId?: string) {
  return useQuery<WorkloadRow[]>({
    queryKey: [...KEY, 'workload', projectId],
    queryFn: async () => (await api.get('/project-reports/workload', { params: clean({ projectId }) })).data,
  });
}

export function useGantt(projectId: string | undefined) {
  return useQuery<Gantt>({
    queryKey: [...KEY, 'gantt', projectId],
    enabled: !!projectId,
    queryFn: async () => (await api.get('/project-reports/gantt', { params: { projectId } })).data,
  });
}

// ---- mutations --------------------------------------------------------------------------------------------------------

type Method = 'post' | 'patch' | 'put' | 'delete';

/**
 * One mutation hook for every PM write: sends the request, toasts the server's verbatim error and refreshes all
 * PM queries (boards, task lists, the open task, timers and reports read the same rows).
 */
export function usePmMutation<V = void, R = unknown>(
  build: (v: V) => { method: Method; url: string; body?: unknown },
  opts: { success?: string; silent?: boolean } = {},
) {
  const qc = useQueryClient();
  return useMutation<R, unknown, V>({
    mutationFn: async (v: V) => {
      const { method, url, body } = build(v);
      const res = method === 'delete' ? await api.delete(url) : await api[method](url, body ?? {});
      return res.data as R;
    },
    onSuccess: () => {
      if (opts.success) notify.success(opts.success);
      void qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (err) => {
      if (!opts.silent) notify.error(apiMessage(err, tx('Something went wrong')));
    },
  });
}

export const pmApi = {
  createProject: (body: Record<string, unknown>) => ({ method: 'post' as const, url: '/projects/workspace', body }),
  updateProject: (id: string, body: Record<string, unknown>) => ({ method: 'patch' as const, url: `/projects/${id}/workspace`, body }),
  createTask: (body: Record<string, unknown>) => ({ method: 'post' as const, url: '/project-tasks', body }),
  updateTask: (id: string, body: Record<string, unknown>) => ({ method: 'patch' as const, url: `/project-tasks/${id}`, body }),
};
