import { apiClient } from './client';
import { ApiResponse, Repository, AnalysisJob } from '../types';

export interface ConnectRepositoryPayload {
  connectedAccountId?: string;
  fullName: string;
  branch?: string;
}

export const repositoriesApi = {
  list: async (page = 0, size = 20): Promise<{ content: Repository[]; totalElements: number }> => {
    const res = await apiClient.get<ApiResponse<any>>('/repositories', {
      params: { page, size },
    });
    return res.data.data;
  },

  getById: async (id: string): Promise<Repository> => {
    const res = await apiClient.get<ApiResponse<Repository>>(`/repositories/${id}`);
    return res.data.data;
  },

  connect: async (payload: ConnectRepositoryPayload): Promise<Repository> => {
    const res = await apiClient.post<ApiResponse<Repository>>('/repositories/connect', payload);
    return res.data.data;
  },

  triggerIndexing: async (id: string): Promise<{ jobId: string }> => {
    const res = await apiClient.post<ApiResponse<{ jobId: string }>>(`/repositories/${id}/index`);
    return res.data.data;
  },

  getIndexStatus: async (id: string): Promise<Record<string, any>> => {
    const res = await apiClient.get<ApiResponse<Record<string, any>>>(`/repositories/${id}/index-status`);
    return res.data.data;
  },

  listJobs: async (repositoryId: string, page = 0, size = 20): Promise<{ content: AnalysisJob[] }> => {
    try {
      const res = await apiClient.get<ApiResponse<any>>(`/jobs/repository/${repositoryId}`, {
        params: { page, size },
      });
      return res.data?.data || { content: [] };
    } catch {
      return { content: [] };
    }
  },

  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/repositories/${id}`);
  },
};

