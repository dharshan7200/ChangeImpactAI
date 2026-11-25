import { apiClient } from './client';
import type { ApiResponse } from '../types';
import type {
  GitHubAppStatus, PullRequest, QualityReport, ReportType, SecurityResponse, TeamDashboard, TestsResponse, WebhookDelivery,
} from '../types/phase5';

export const qualityApi = {
  tests: async (analysisId: string): Promise<TestsResponse> =>
    (await apiClient.get<ApiResponse<TestsResponse>>(`/analyses/${analysisId}/tests`)).data.data,

  security: async (analysisId: string): Promise<SecurityResponse> =>
    (await apiClient.get<ApiResponse<SecurityResponse>>(`/analyses/${analysisId}/security`)).data.data,

  listReports: async (repositoryId: string): Promise<QualityReport[]> =>
    (await apiClient.get<ApiResponse<QualityReport[]>>(`/repositories/${repositoryId}/reports`)).data.data,

  uploadReport: async (repositoryId: string, type: ReportType, commitSha: string, files: File[]): Promise<QualityReport> => {
    const form = new FormData();
    form.append('type', type);
    form.append('commitSha', commitSha);
    files.forEach((f) => form.append('file', f));
    // the client's JSON default would make axios serialise FormData to JSON; the browser sets the multipart boundary
    return (await apiClient.post<ApiResponse<QualityReport>>(`/repositories/${repositoryId}/reports`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).data.data;
  },
};

export const githubAppApi = {
  status: async (): Promise<GitHubAppStatus> => (await apiClient.get<ApiResponse<GitHubAppStatus>>('/github/app')).data.data,
  deliveries: async (): Promise<WebhookDelivery[]> =>
    (await apiClient.get<ApiResponse<WebhookDelivery[]>>('/github/app/deliveries')).data.data,
  pullRequests: async (repositoryId: string): Promise<PullRequest[]> =>
    (await apiClient.get<ApiResponse<PullRequest[]>>(`/repositories/${repositoryId}/pull-requests`)).data.data,
};

export const dashboardApi = {
  team: async (days = 30): Promise<TeamDashboard> =>
    (await apiClient.get<ApiResponse<TeamDashboard>>('/dashboard', { params: { days } })).data.data,
};
