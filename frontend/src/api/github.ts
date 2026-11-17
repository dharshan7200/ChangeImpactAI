import { apiClient } from './client';
import { ApiResponse, ConnectedAccount, GitHubRepository } from '../types';

export const githubApi = {
  connect: async (accessToken: string): Promise<ConnectedAccount> => {
    const res = await apiClient.post<ApiResponse<ConnectedAccount>>('/github/connect', { accessToken });
    return res.data.data;
  },

  listAccounts: async (): Promise<ConnectedAccount[]> => {
    const res = await apiClient.get<ApiResponse<ConnectedAccount[]>>('/github/accounts');
    return res.data.data;
  },

  listRemoteRepos: async (accountId: string, page = 1): Promise<GitHubRepository[]> => {
    try {
      const res = await apiClient.get<ApiResponse<GitHubRepository[]>>(`/github/accounts/${accountId}/repos`, {
        params: { page },
      });
      return res.data?.data || [];
    } catch {
      return [];
    }
  },

  disconnect: async (accountId: string): Promise<void> => {
    await apiClient.delete(`/github/accounts/${accountId}`);
  },
};

