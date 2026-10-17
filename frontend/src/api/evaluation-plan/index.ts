import { get, post, put, del } from '@/utils/request'

export interface ScoringDimension { id: string; name: string; description: string; weight: number }
export interface QAGenerationRules {
  questions_per_chunk: number
  question_types: string[]
  difficulty: string
  language: string
}
export interface EvaluationPlanInput {
  name: string
  description: string
  qa_generation_prompt: string
  answer_comparison_prompt: string
  qa_generation_model_id: string
  evaluation_model_id: string
  scoring_dimensions: ScoringDimension[]
  pass_score: number
  qa_generation_rules: QAGenerationRules
}
export interface EvaluationPlan extends EvaluationPlanInput {
  id: string
  created_by: string
  creator_name: string
  can_manage: boolean
  created_at: string
  updated_at: string
}
export interface EvaluationPlanPage { items: EvaluationPlan[]; total: number; page: number; page_size: number }
interface Result<T> { success: boolean; data: T }
const base = '/api/v1/evaluation-plans'
function result<T>(response: Result<T>): T {
  if (!response.success) throw new Error('Evaluation plan operation failed')
  return response.data
}
export const listEvaluationPlans = async (keyword: string, page: number, pageSize: number) =>
  result(await get<Result<EvaluationPlanPage>>(base, { params: { keyword, page, page_size: pageSize } }))
export const getEvaluationPlan = async (id: string) => result(await get<Result<EvaluationPlan>>(`${base}/${encodeURIComponent(id)}`))
export const createEvaluationPlan = async (input: EvaluationPlanInput) => result(await post<Result<EvaluationPlan>>(base, input))
export const updateEvaluationPlan = async (id: string, input: EvaluationPlanInput) => result(await put<Result<EvaluationPlan>>(`${base}/${encodeURIComponent(id)}`, input))
export const deleteEvaluationPlan = async (id: string) => result(await del<Result<null>>(`${base}/${encodeURIComponent(id)}`))
export const copyEvaluationPlan = async (id: string) => result(await post<Result<EvaluationPlan>>(`${base}/${encodeURIComponent(id)}/copy`))
