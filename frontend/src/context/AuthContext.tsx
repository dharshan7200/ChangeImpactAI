import React, { createContext, useContext, useState, useEffect } from 'react';
import { UserSummary, AuthResponse } from '../types';
import { authApi } from '../api/auth';

interface AuthContextType {
  user: UserSummary | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, pass: string) => Promise<void>;
  register: (email: string, user: string, pass: string, name?: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const savedUser = localStorage.getItem('user_info');
    const token = localStorage.getItem('access_token');
    if (savedUser && token) {
      try {
        setUser(JSON.parse(savedUser));
      } catch {
        localStorage.removeItem('user_info');
        localStorage.removeItem('access_token');
      }
    }
    setIsLoading(false);
  }, []);

  const handleAuthSuccess = (res: AuthResponse) => {
    localStorage.setItem('access_token', res.accessToken);
    if (res.refreshToken) {
      localStorage.setItem('refresh_token', res.refreshToken);
    }
    localStorage.setItem('user_info', JSON.stringify(res.user));
    setUser(res.user);
  };

  const login = async (email: string, pass: string) => {
    const res = await authApi.login(email, pass);
    handleAuthSuccess(res);
  };

  const register = async (email: string, username: string, pass: string, name?: string) => {
    const res = await authApi.register(email, username, pass, name);
    handleAuthSuccess(res);
  };

  const logout = () => {
    const rt = localStorage.getItem('refresh_token');
    if (rt) {
      authApi.logout(rt).catch(() => {});
    }
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('user_info');
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, isLoading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

