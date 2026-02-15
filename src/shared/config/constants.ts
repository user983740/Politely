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
  { key: 'BILLING', label: '비용/정산' },
  { key: 'SUPPORT', label: '기술지원' },
  { key: 'CONTRACT', label: '계약' },
  { key: 'RECRUITING', label: '채용' },
  { key: 'CIVIL_COMPLAINT', label: '민원' },
  { key: 'GRATITUDE', label: '감사' },
] as const;

export const TONE_LEVELS = [
  { key: 'NEUTRAL', label: '중립' },
  { key: 'POLITE', label: '공손' },
  { key: 'VERY_POLITE', label: '매우 공손' },
] as const;

export const TOPICS = [
  { key: 'REFUND_CANCEL', label: '환불/취소' },
  { key: 'OUTAGE_ERROR', label: '장애/오류' },
  { key: 'ACCOUNT_PERMISSION', label: '계정/권한' },
  { key: 'DATA_FILE', label: '데이터/파일' },
  { key: 'SCHEDULE_DEADLINE', label: '일정/납기' },
  { key: 'COST_BILLING', label: '비용/정산' },
  { key: 'CONTRACT_TERMS', label: '계약/약관' },
  { key: 'HR_EVALUATION', label: '인사/평가' },
  { key: 'ACADEMIC_GRADE', label: '학사/성적' },
  { key: 'COMPLAINT_REGULATION', label: '민원/규정' },
  { key: 'OTHER', label: '기타' },
] as const;

export const PURPOSES = [
  { key: 'INFO_DELIVERY', label: '정보 전달' },
  { key: 'DATA_REQUEST', label: '자료 요청' },
  { key: 'SCHEDULE_COORDINATION', label: '일정 조율' },
  { key: 'APOLOGY_RECOVERY', label: '사과/수습' },
  { key: 'RESPONSIBILITY_SEPARATION', label: '책임 분리' },
  { key: 'REJECTION_NOTICE', label: '거절/불가 안내' },
  { key: 'REFUND_REJECTION', label: '환불 거절' },
  { key: 'WARNING_PREVENTION', label: '경고/재발 방지' },
  { key: 'RELATIONSHIP_RECOVERY', label: '관계 회복' },
  { key: 'NEXT_ACTION_CONFIRM', label: '다음 행동 확정' },
  { key: 'ANNOUNCEMENT', label: '공지/안내' },
] as const;

export const MAX_TEXT_LENGTH = 1000;
export const MAX_PROMPT_LENGTH = 500;
export const MAX_SENDER_INFO_LENGTH = 100;
export const API_BASE_URL = '/api';

export type Persona = (typeof PERSONAS)[number]['key'];
export type Context = (typeof CONTEXTS)[number]['key'];
export type ToneLevel = (typeof TONE_LEVELS)[number]['key'];
export type Topic = (typeof TOPICS)[number]['key'];
export type Purpose = (typeof PURPOSES)[number]['key'];
