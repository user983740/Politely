export const PERSONAS = [
  { key: 'BOSS', label: '직장 상사' },
  { key: 'CLIENT', label: '고객' },
  { key: 'PARENT', label: '학부모' },
  { key: 'PROFESSOR', label: '교수' },
  { key: 'COLLEAGUE', label: '동료' },
  { key: 'OFFICIAL', label: '공식 기관' },
] as const;

export const CONTEXTS = [
  { key: 'REQUEST', label: '요청' },
  { key: 'SCHEDULE_DELAY', label: '일정 지연' },
  { key: 'URGING', label: '독촉' },
  { key: 'REJECTION', label: '거절' },
  { key: 'APOLOGY', label: '사과' },
  { key: 'COMPLAINT', label: '항의' },
  { key: 'ANNOUNCEMENT', label: '공지' },
  { key: 'FEEDBACK', label: '피드백' },
] as const;

export const TONE_LEVELS = [
  { key: 'VERY_POLITE', label: '매우 공손' },
  { key: 'POLITE', label: '공손' },
  { key: 'NEUTRAL', label: '중립' },
  { key: 'FIRM_BUT_RESPECTFUL', label: '단호하지만 예의있게' },
] as const;

export const MAX_TEXT_LENGTH = 1000;
export const MAX_PROMPT_LENGTH = 80;
export const API_BASE_URL = '/api';

export type Persona = (typeof PERSONAS)[number]['key'];
export type Context = (typeof CONTEXTS)[number]['key'];
export type ToneLevel = (typeof TONE_LEVELS)[number]['key'];
