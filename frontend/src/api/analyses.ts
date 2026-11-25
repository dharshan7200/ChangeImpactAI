import { apiClient } from './client';
import type {
  AnalysisReport,
  ApiResponse,
  ChangeAnalysis,
  ChangedFileView,
  ChangedSymbolView,
  CommitInfo,
  ImpactFindingView,
  RepositoryHistory,
  RiskFactor,
} from '../types';

export interface CreateAnalysisPayload {
  repositoryId: string;
  baseRef: string;
  headRef: string;
  maxDepth?: number;
  relationshipTypes?: string[];
  useMergeBase?: boolean;
}

interface Page<T> {
  content: T[];
  totalElements: number;
}

export const analysesApi = {
  create: async (payload: CreateAnalysisPayload): Promise<{ analysisId: string; jobId: string }> => {
    const res = await apiClient.post<ApiResponse<{ analysisId: string; jobId: string }>>('/analyses', payload);
    return res.data.data;
  },

  list: async (repositoryId: string, page = 0, size = 20): Promise<Page<ChangeAnalysis>> => {
    const res = await apiClient.get<ApiResponse<Page<ChangeAnalysis>>>('/analyses', { params: { repositoryId, page, size } });
    return res.data.data;
  },

  get: async (id: string): Promise<ChangeAnalysis> => {
    const res = await apiClient.get<ApiResponse<ChangeAnalysis>>(`/analyses/${id}`);
    return res.data.data;
  },

  changes: async (id: string): Promise<{ files: ChangedFileView[]; symbols: ChangedSymbolView[] }> => {
    const res = await apiClient.get<ApiResponse<{ files: ChangedFileView[]; symbols: ChangedSymbolView[] }>>(`/analyses/${id}/changes`);
    return res.data.data;
  },

  impacts: async (
    id: string,
    params: { changedSymbolId?: string; impactType?: string; includeTests?: boolean; size?: number } = {}
  ): Promise<Page<ImpactFindingView>> => {
    const res = await apiClient.get<ApiResponse<Page<ImpactFindingView>>>(`/analyses/${id}/impacts`, {
      params: { size: 500, ...params },
    });
    return res.data.data;
  },

  risk: async (
    id: string
  ): Promise<{ score: number; level: string; disclaimer: string; symbols: { changedSymbolId: string; score: number; level: string; factors: RiskFactor[] }[] }> => {
    const res = await apiClient.get<ApiResponse<any>>(`/analyses/${id}/risk`);
    return res.data.data;
  },

  report: async (id: string): Promise<AnalysisReport> => {
    const res = await apiClient.get<ApiResponse<AnalysisReport>>(`/analyses/${id}/report`);
    return res.data.data;
  },

  reportMarkdown: async (id: string): Promise<string> => {
    const res = await apiClient.get<string>(`/analyses/${id}/report.md`, { responseType: 'text' });
    return res.data;
  },

  commits: async (repositoryId: string, size = 100): Promise<Page<CommitInfo>> => {
    const res = await apiClient.get<ApiResponse<Page<CommitInfo>>>(`/repositories/${repositoryId}/commits`, { params: { size } });
    return res.data.data;
  },

  history: async (repositoryId: string, weeks = 52): Promise<RepositoryHistory> => {
    const res = await apiClient.get<ApiResponse<RepositoryHistory>>(`/repositories/${repositoryId}/history`, { params: { weeks } });
    return res.data.data;
  },
};
