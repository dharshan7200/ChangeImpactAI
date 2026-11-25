import { apiClient } from './client';
import type { ApiResponse } from '../types';
import type { DefectModel } from '../types/defect';

export const defectApi = {
  /** null when no model has been trained yet (`{status: "NOT_TRAINED"}`). */
  get: async (repoId: string): Promise<DefectModel | null> => {
    const model = (await apiClient.get<ApiResponse<DefectModel | { status: 'NOT_TRAINED' }>>(`/repositories/${repoId}/defect-model`)).data.data;
    return model.status === 'NOT_TRAINED' ? null : (model as DefectModel);
  },
  train: async (repoId: string): Promise<{ jobId: string }> =>
    (await apiClient.post<ApiResponse<{ jobId: string }>>(`/repositories/${repoId}/defect-model/train`)).data.data,
};
