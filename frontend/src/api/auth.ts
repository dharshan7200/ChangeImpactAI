import { apiClient } from './client';
import { ApiResponse, AuthResponse } from '../types';

export const authApi = {
  login: async (email: string, password: string): Promise<AuthResponse> => {
    const res = await apiClient.post<ApiResponse<AuthResponse>>('/auth/login', { email, password });
    return res.data.data;
  },

  register: async (email: string, username: string, password: string, displayName?: string): Promise<AuthResponse> => {
    const res = await apiClient.post<ApiResponse<AuthResponse>>('/auth/register', {
      email,
      username,
      password,
      displayName,
    });
    return res.data.data;
  },

  logout: async (refreshToken: string): Promise<void> => {
    await apiClient.post('/auth/logout', { refreshToken });
  },
};

