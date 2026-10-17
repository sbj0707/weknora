import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { defaultPlan, editablePlan, validatePlan } from './evaluationPlanConfig'

test('plan form keeps literal prompt variables and projects only editable data from Vue state', () => {
  const base = defaultPlan('zh-CN')
  base.name = 'Plan'
  base.qa_generation_model_id = base.evaluation_model_id = 'chat'
  const state = reactive({ ...base, id: 'server-guid', created_by: 'owner', can_manage: true })
  const payload = editablePlan(state)
  assert.equal(validatePlan(payload, new Set(['chat'])), null)
  assert.equal('id' in payload, false)
  assert.equal('created_by' in payload, false)
  assert.equal(payload.qa_generation_prompt, state.qa_generation_prompt)
  assert.ok(payload.qa_generation_prompt.includes('{{content}}'))
  payload.scoring_dimensions[0]!.weight = 1
  payload.qa_generation_rules.question_types.length = 0
  assert.equal(state.scoring_dimensions[0]!.weight, 40)
  assert.equal(state.qa_generation_rules.question_types.length, 4)
})

test('plan configuration validation rejects unavailable models and malformed scoring rules', () => {
  function valid() { const p = defaultPlan('en-US'); p.name = 'Plan'; p.qa_generation_model_id = p.evaluation_model_id = 'chat'; return p }
  const available = new Set(['chat'])
  assert.equal(validatePlan(valid(), new Set()), 'models')
  const weights = valid(); weights.scoring_dimensions[0]!.weight = 39
  assert.equal(validatePlan(weights, available), 'dimensions')
  const duplicate = valid(); duplicate.scoring_dimensions[1]!.id = duplicate.scoring_dimensions[0]!.id
  assert.equal(validatePlan(duplicate, available), 'dimensions')
  const fractional = valid(); fractional.qa_generation_rules.questions_per_chunk = 1.5
  assert.equal(validatePlan(fractional, available), 'rules')
  const types = valid(); types.qa_generation_rules.question_types = ['fact', 'fact']
  assert.equal(validatePlan(types, available), 'rules')
  const zero = valid(); zero.pass_score = 0
  assert.equal(validatePlan(zero, available), null)
  zero.pass_score = Number.NaN
  assert.equal(validatePlan(zero, available), 'score')
})
