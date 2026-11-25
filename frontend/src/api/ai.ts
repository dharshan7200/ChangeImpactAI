import { apiClient } from './client';
import type { ApiResponse } from '../types';

export interface EvidenceRef { type?: string; kind?: string; id?: string; name: string; file?: string; startLine?: number; endLine?: number; score?: number; ftsRank?: number; vectorRank?: number }
export interface AiFinding { title: string; severity: 'HIGH' | 'MEDIUM' | 'LOW'; category: string; explanation: string; suggestion: string; citations: string[]; status: 'VERIFIED' | 'UNVERIFIED'; reason?: string; unknownMentions?: string[]; invalidCitations?: string[] }
export interface AiReview {
  status: 'NOT_REQUESTED' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  aiEnabled?: boolean;
  id?: string; model?: string; promptVersion?: string; promptTokens?: number; outputTokens?: number; latencyMs?: number;
  errorMessage?: string; createdAt?: string; completedAt?: string;
  evidence?: Record<string, EvidenceRef>;
  result?: { summary: string; findings: AiFinding[]; testSuggestions: { title: string; rationale: string; citations: string[]; status: string }[]; questionsForAuthor: string[] };
  validation?: { findings: number; verified: number; unverified: number; invalidCitations: number; unknownMentions: number; evidenceItems: number; relatedCodeRetrieval?: string };
}
export interface RagStatus { aiEnabled: boolean; commit: string | null; chunks: number; candidates: number; embeddingModel: string }
export interface SearchHit { chunkId: string; symbolId: string | null; qualifiedName: string; symbolType: string; filePath: string; startLine: number; endLine: number; content: string; ftsRank: number | null; vectorRank: number | null; similarity: number | null; score: number }
export interface SearchResult { hits: SearchHit[]; mode: 'fts' | 'vector' | 'hybrid'; note?: string | null }
export interface AssistantAnswer { id?: string; question: string; answer: string; status: 'VERIFIED' | 'UNVERIFIED' | 'INSUFFICIENT_EVIDENCE'; citations: string[]; unknownMentions?: string[]; evidence: EvidenceRef[]; model: string; promptTokens: number; outputTokens: number; latencyMs: number; createdAt?: string; retrieval?: string }

export const aiApi = {
  review: async (analysisId: string): Promise<AiReview> => (await apiClient.get<ApiResponse<AiReview>>(`/analyses/${analysisId}/ai-review`)).data.data,
  startReview: async (analysisId: string) => (await apiClient.post<ApiResponse<{ reviewId: string; jobId: string }>>(`/analyses/${analysisId}/ai-review`)).data.data,
  ragStatus: async (repoId: string): Promise<RagStatus> => (await apiClient.get<ApiResponse<RagStatus>>(`/repositories/${repoId}/rag/status`)).data.data,
  ragIndex: async (repoId: string) => (await apiClient.post<ApiResponse<{ jobId: string }>>(`/repositories/${repoId}/rag/index`)).data.data,
  search: async (repoId: string, query: string, mode: string, k = 10): Promise<SearchResult> =>
    (await apiClient.post<ApiResponse<SearchResult>>(`/repositories/${repoId}/rag/search`, { query, mode, k })).data.data,
  ask: async (repoId: string, question: string): Promise<AssistantAnswer> =>
    (await apiClient.post<ApiResponse<AssistantAnswer>>(`/repositories/${repoId}/assistant/ask`, { question }, { timeout: 300000 })).data.data,
  history: async (repoId: string): Promise<AssistantAnswer[]> => (await apiClient.get<ApiResponse<AssistantAnswer[]>>(`/repositories/${repoId}/assistant/history`)).data.data,
};
