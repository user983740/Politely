export const PERSONAS = [
  { key: 'BOSS', label: '직장 상사' },
  { key: 'CLIENT', label: '고객' },
  { key: 'PARENT', label: '학부모' },
  { key: 'PROFESSOR', label: '교수' },
  { key: 'OFFICIAL', label: '공식 기관' },
  { key: 'OTHER', label: '기타' },
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
  { key: 'NEUTRAL', label: '중립' },
  { key: 'POLITE', label: '공손' },
  { key: 'VERY_POLITE', label: '매우 공손' },
] as const;

export const MAX_TEXT_LENGTH = 1000;
export const MAX_PROMPT_LENGTH = 500;
export const MAX_SENDER_INFO_LENGTH = 100;
export const API_BASE_URL = '/api';

export type Persona = (typeof PERSONAS)[number]['key'];
export type Context = (typeof CONTEXTS)[number]['key'];
export type ToneLevel = (typeof TONE_LEVELS)[number]['key'];
