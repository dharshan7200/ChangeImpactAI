import { apiClient } from './client';
import type { ApiResponse } from '../types';
import type { AnalysedCommit, TwinComparison, TwinHotspots, TwinSnapshot, TwinTrendPoint } from '../types/twin';

const base = (repoId: string) => `/repositories/${repoId}/architecture`;

export const twinApi = {
  commits: async (repoId: string): Promise<AnalysedCommit[]> =>
    (await apiClient.get<ApiResponse<AnalysedCommit[]>>(`${base(repoId)}/commits`)).data.data,
  snapshot: async (repoId: string, commit?: string): Promise<TwinSnapshot> =>
    (await apiClient.get<ApiResponse<TwinSnapshot>>(`${base(repoId)}/snapshot`, { params: { commit } })).data.data,
  hotspots: async (repoId: string, commit?: string): Promise<TwinHotspots> =>
    (await apiClient.get<ApiResponse<TwinHotspots>>(`${base(repoId)}/hotspots`, { params: { commit } })).data.data,
  trend: async (repoId: string): Promise<TwinTrendPoint[]> =>
    (await apiClient.get<ApiResponse<TwinTrendPoint[]>>(`${base(repoId)}/trend`)).data.data,
  compare: async (repoId: string, baseCommit: string, headCommit: string): Promise<TwinComparison> =>
    (await apiClient.get<ApiResponse<TwinComparison>>(`${base(repoId)}/compare`, { params: { base: baseCommit, head: headCommit } })).data.data,
  backfill: async (repoId: string, commits: number): Promise<{ jobId: string; commits: number }> =>
    (await apiClient.post<ApiResponse<{ jobId: string; commits: number }>>(`${base(repoId)}/backfill`, { commits })).data.data,
};
