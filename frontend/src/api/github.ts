import { apiClient } from './client';
import { ApiResponse, ConnectedAccount, GitHubRepository } from '../types';

export const githubApi = {
  connect: async (accessToken: string): Promise<ConnectedAccount> => {
    const res = await apiClient.post<ApiResponse<ConnectedAccount>>('/github/connect', { accessToken });
    return res.data.data;
  },

  oauthConfig: async (): Promise<{ configured: boolean; scopes: string; callbackUrl: string }> =>
    (await apiClient.get<ApiResponse<{ configured: boolean; scopes: string; callbackUrl: string }>>('/github/oauth/config')).data.data,

  /** GitHub consent page URL (signed, single-use state). */
  oauthAuthorizeUrl: async (): Promise<string> =>
    (await apiClient.get<ApiResponse<{ url: string }>>('/github/oauth/authorize')).data.data.url,

  oauthComplete: async (code: string, state: string): Promise<ConnectedAccount> =>
    (await apiClient.post<ApiResponse<ConnectedAccount>>('/github/oauth/callback', { code, state })).data.data,

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

