import { apiClient } from './client';
import { ApiResponse, CodeSymbol, GraphSnapshot, ArchitectureViolation, CouplingMetrics } from '../types';

export const codeApi = {
  searchSymbols: async (
    repoId: string,
    symbolType?: string,
    query?: string,
    page = 0,
    size = 30
  ): Promise<{ content: CodeSymbol[]; totalElements: number }> => {
    const res = await apiClient.get<ApiResponse<any>>(`/code/repositories/${repoId}/symbols`, {
      params: { symbolType, query, page, size },
    });
    return res.data.data;
  },

  getSymbol: async (symbolId: string): Promise<CodeSymbol> => {
    const res = await apiClient.get<ApiResponse<CodeSymbol>>(`/code/symbols/${symbolId}`);
    return res.data.data;
  },

  getDependencies: async (symbolId: string, depth = 1): Promise<any[]> => {
    const res = await apiClient.get<ApiResponse<any[]>>(`/code/symbols/${symbolId}/dependencies`, {
      params: { depth },
    });
    return res.data.data;
  },

  getDependents: async (symbolId: string, depth = 1): Promise<any[]> => {
    const res = await apiClient.get<ApiResponse<any[]>>(`/code/symbols/${symbolId}/dependents`, {
      params: { depth },
    });
    return res.data.data;
  },

  getGraphSnapshot: async (repoId: string, packageFilter?: string): Promise<GraphSnapshot> => {
    const res = await apiClient.get<ApiResponse<GraphSnapshot>>(`/code/repositories/${repoId}/graph`, {
      params: { packageFilter },
    });
    return res.data.data;
  },

  getShortestPath: async (repoId: string, sourceSymbolId: string, targetSymbolId: string): Promise<any> => {
    const res = await apiClient.get<ApiResponse<any>>(`/code/repositories/${repoId}/paths`, {
      params: { sourceSymbolId, targetSymbolId },
    });
    return res.data.data;
  },

  getArchitectureViolations: async (repoId: string): Promise<ArchitectureViolation[]> => {
    const res = await apiClient.get<ApiResponse<ArchitectureViolation[]>>(`/code/repositories/${repoId}/architecture/violations`);
    return res.data.data;
  },

  getCouplingMetrics: async (repoId: string): Promise<CouplingMetrics[]> => {
    const res = await apiClient.get<ApiResponse<CouplingMetrics[]>>(`/code/repositories/${repoId}/metrics`);
    return res.data.data;
  },
};

