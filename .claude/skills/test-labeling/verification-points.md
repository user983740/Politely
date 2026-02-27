# Labeling Verification Points

> **판단 원칙**: 표현이 아닌 내용으로 판단한다. 거친 표현이어도 전달해야 할 내용이면 GREEN. 내용 자체가 변명/비난/감정 토로면 YELLOW. 내용이 불필요하거나 해로우면 RED.

### S1: 학부모/피드백

| Segment | Expected | Note |
|---------|----------|------|
| 안녕하쇼 담임임. | GREEN(COURTESY) | 인사. 비표준 표현이나 내용은 인사 기능 |
| 님 애가 시험을 망해서 놀라셨죠. | GREEN(CORE_FACT) | 시험 결과 사실 전달 + 상대 상황 공감. 내용상 필수 정보 |
| 근데 내 탓하려고 시동걸거 같아서 말해두는데 | YELLOW(SELF_JUSTIFICATION) | 내용이 선제적 자기변호 — 상대 의도를 추측하며 방어 구조 전개 |
| 난 숙제도 제대로 내주고...스터디 플랜도 잘 짜줬다. | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 역할 수행 강조 — 방어적 사실 나열이므로 YELLOW. RED는 아님(사실 포함) |
| 근데 님 애가 안 쳐한거임. | YELLOW(NEGATIVE_FEEDBACK) | 내용이 상대방(학생) 행동에 대한 직접 판단. GREEN(CORE_FACT)이 아닌 이유: 객관적 사실이 아니라 평가 |
| 학원 바꿀꺼면 머 ㅃㅃ인데 못바꾸잔아 | RED(PURE_GRUMBLE) | 내용이 체념/투덜거림. 전달 필요 없는 감정 토로. ㅃㅃ는 맥락상 체념이며 AGGRESSION 아님 |
| 걍 다음에 잘 보고 싶으면 님도 애를 좀 잡아봐 숙제를 하게. | GREEN(REQUEST)+YELLOW(NEG_FEEDBACK) | 내용 혼합: 요청(숙제 관리)+지시. 분절 시 분리 기대 |

### S2: 직장상사/사과, 항의

| Segment | Expected | Note |
|---------|----------|------|
| 솔직히 말해서...저 때문만은 아니고 | YELLOW(SELF_JUSTIFICATION) | 내용이 책임 회피 — 자기 탓이 아님을 선제적으로 주장 |
| PR-482도 아직 안 닫혔고 김민수도 답을 안 줬고 | YELLOW(ACCOUNTABILITY/SELF_JUST) | 내용이 타인 귀책 나열. 사실이나 맥락상 책임 전가 목적 |
| v2.3.1 배포가 지금 이 꼴 난건데 | YELLOW(EMOTIONAL) | 내용이 상황에 대한 감정적 평가. 사실적 맥락이 있으므로 RED 아님 |
| 제가 다 책임져야 하는 분위기라 좀 억울하긴 합니다 | YELLOW(EMOTIONAL) | 내용이 불공정함에 대한 감정 표현. 상황 맥락(책임 분배)이 있으므로 YELLOW |
| 오늘 18:00 전에 report_final_v3.docx는 올리긴 올릴거고 | GREEN(CORE_INTENT) | 핵심 약속 — 구체적 행동 의지 |
| 링크는 https://drive.company.com/alpha...이메일... | GREEN(CORE_FACT) | 사실 정보 전달 |
| 어제도 새벽 3시까지 했고 허리도 아프고 | RED(PRIVATE_TMI) | 내용이 개인 사정 — 업무 맥락에서 전달 불필요 |
| 이거 계속 이런식이면 저도 진짜 모르겠습니다 | RED(PURE_GRUMBLE) | 내용이 순수 감정 토로 — 구체적 제안 없음 |
| 일단 오늘은 넘기고...구조 다시 갈아엎든지 하죠 | GREEN(CORE_INTENT) | 내용이 향후 제안. 표현이 거칠어도 내용은 건설적 |

### S3: 고객/요청, 피드백

| Segment | Expected | Note |
|---------|----------|------|
| 고객님 솔직히 말씀드리면 | YELLOW(ACCOUNTABILITY) | 호칭 포함, 내용상 지배 기능이 방어적 개시이므로 해당 라벨 기준 |
| 이번 오류는...귀사 서버 설정이 이상해서 생긴거고 | YELLOW(ACCOUNTABILITY) | 내용이 고객 측 귀책 지적 — 사실 포함이나 비난 기능 |
| 지난번에도...건드리지 말라고 했는데 또 수정하셔서... | YELLOW(NEGATIVE_FEEDBACK) | 내용이 고객 행동에 대한 직접 판단 + 반복 지적 |
| 010-4921-8823으로...계좌는 110-123-456789이고 | GREEN(CORE_FACT) | 연락처/계좌 정보 — 필수 사실 전달 |
| 환불 얘기는 좀 과한 것 같고 | YELLOW(NEGATIVE_FEEDBACK) | 내용이 고객 요구에 대한 거절/판단. 비즈니스 입장 표명이므로 재구성 대상 |
| 로그파일 error_dump_2026_02_14.zip 보내주시면 제가 보겠습니다 | GREEN(REQUEST) | 구체적 요청 + 후속 조치 제안 |
| 저도 사람이라...멘탈 좀 나가긴 합니다 | RED(PRIVATE_TMI/PURE_GRUMBLE) | 내용이 개인 감정 — 고객에게 전달 불필요 |

### S4: 교수/사과

| Segment | Expected | Note |
|---------|----------|------|
| 교수님...사실 제 잘못도 있지만 | GREEN(APOLOGY)/YELLOW(SELF_JUST) | 내용 혼합: 사과 + 변명 구조. 호칭 포함 OK, 지배 기능 기준 라벨링 |
| JIRA-991 이 계속 꼬여서 그런거고 | YELLOW(SELF_JUSTIFICATION) | 내용이 외부 원인 귀속 — 자기변호 |
| 개인적으로도 집안일이 좀 있었고...정신이 없었습니다 | RED(PRIVATE_TMI) | 내용이 교수에게 불필요한 개인 사정 |
| 결과물은 final_thesis_v1.0.4.pdf로 정리해놨고 | GREEN(CORE_FACT) | 핵심 산출물 정보 |
| https://snu-project-lab.com/upload 여기 업로드 예정인데 | GREEN(CORE_INTENT) | 핵심 행동 계획 |
| 일정이 빡빡하면 연구 퀄리티가 떨어질 수밖에 없다 | RED(PURE_GRUMBLE) | 내용이 학생→교수 비판. 맥락상 부적절하며 건설적 내용 없음 |
| UUID는 123e4567-e89b-12d3-a456-426614174000 이고 | GREEN(CORE_FACT) | 사실 정보 |
| 어쨌든 늦은건 죄송하지만 | GREEN(APOLOGY) | 내용이 사과. 뒤에 변명이 붙어도 사과 기능 자체는 유효 |
| 저만 탓받는 느낌은 좀 억울하네요 | RED(PURE_GRUMBLE)/YELLOW(EMOTIONAL) | 내용이 교수 대상 감정 표출 — 관계상 부적절, RED 쪽. 사실 맥락 있으면 YELLOW |

### S5: 고객(환불)/결제, 항의

| Segment | Expected | Note |
|---------|----------|------|
| 이번 장애가 불편하셨던 건 이해함 | GREEN(COURTESY) | 내용이 공감/인정 — 수신자 상황 수용 |
| SLA 범위 벗어난 건 아니고 약관에도 명시되어 있음 | GREEN(CORE_FACT)/YELLOW(SELF_JUST) | 내용이 계약상 사실(SLA, 약관). 단 방어적 목적으로 사용 시 SELF_JUST. Boundary |
| 저희도 어제 새벽 2시까지 복구 작업했고요 | YELLOW(SELF_JUST)/RED(PRIVATE_TMI) | 내용이 자기 노력 강조 — 고객에게 전달 필요성 낮음. Boundary |
| 전액 환불은 좀 과한 요구 같고 | YELLOW(NEGATIVE_FEEDBACK) | 내용이 고객 요구에 대한 판단/거절 — 재구성해야 정중해짐 |
| 부분 크레딧 정도는 내부 검토해볼 수 있음 | GREEN(CORE_INTENT) | 구체적 대안 제시 |
| 계속 이런 식으로 강하게 나오시면...대응이 어렵습니다 | RED(AGGRESSION)/YELLOW(EMOTIONAL) | 내용이 암묵적 위협 — 건설적 내용 없이 상대 행동 억압. RED 쪽 |

### S6: 조원(팀프로젝트)/독촉, 피드백

| Segment | Expected | Note |
|---------|----------|------|
| 이번 발표 망하면 우리 다 점수 날아가는데 | GREEN(CORE_FACT)/YELLOW(EMOTIONAL) | 내용이 팀 성과 영향 경고 — 사실(점수 영향)이나 감정적 압박 기능도 있음. Boundary |
| 준비 상황 보면 좀 답답함 | YELLOW(EMOTIONAL) | 내용이 상대 준비 상황에 대한 감정 표현 |
| 내가 슬라이드 거의 다 만들고 있는데 | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 기여 강조 — 상대와의 불균형 주장 |
| 최소한 스크립트는 알아서 써와야 하는 거 아닌가 싶음 | YELLOW(NEGATIVE_FEEDBACK) | 내용이 상대 기여 부족에 대한 비판. "알아서"는 표현, 내용상 상대 역할 미수행 지적 |
| 나도 시험 기간이라 여유 없음 | YELLOW(SELF_JUST)/GREEN(CORE_FACT) | 내용이 상황 설명이나 맥락상 방어적 사용. Boundary |
| 오늘 밤까지 초안 안 주면...이름만 올릴게 | GREEN(CORE_INTENT)/YELLOW(NEG_FEEDBACK) | 내용 혼합: 기한 제시 + 암묵적 경고. 분절 시 분리 기대 |

### S7: 수강생(온라인)/지원, 피드백

| Segment | Expected | Note |
|---------|----------|------|
| 과제 제출 안 된 건...브라우저 설정 문제 같음 | YELLOW(ACCOUNTABILITY)/GREEN(CORE_FACT) | 내용이 원인 진단이나 수강생 측 귀책으로 전가. Boundary |
| 저희 쪽 서버 로그에는 submit 기록이 없음 | GREEN(CORE_FACT) | 내용이 객관적 기술 사실 |
| 매뉴얼에도 크롬 최신 버전 쓰라고 적어놨고요 | YELLOW(SELF_JUSTIFICATION) | 내용이 "이미 안내했다"는 자기변호 — 사실이나 기능은 방어적 |
| 마감은 이미 지났지만...오늘 자정까지는 다시 열어두겠음 | GREEN(CORE_INTENT) | 내용이 배려 + 구체적 기한 제시 |
| 이런 요청 계속 들어오면 운영이 좀 힘듦 | YELLOW(EMOTIONAL)/RED(PURE_GRUMBLE) | 내용이 업무 부담 토로 — 수강생에게 전달 필요성 낮음. Boundary |

### S8: 알바직원/일정지연, 공지

| Segment | Expected | Note |
|---------|----------|------|
| 스케줄 안 나온 건...본사에서 아직 확정을 안 줘서 | YELLOW(SELF_JUST)+YELLOW(ACCOUNTABILITY) | 내용이 책임 부인 + 본사 귀책 전가 |
| 나도 계속 전화 돌리고 있음 | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 노력 강조 |
| 단톡방에 자꾸...재촉하면 나도 난감함 | YELLOW(NEG_FEEDBACK)/YELLOW(EMOTIONAL) | 내용이 상대 행동 지적 + 감정. Mixed |
| 일단 금요일까지는 줄 건데 | GREEN(CORE_INTENT) | 구체적 일정 약속 |
| 다른 알바 잡는 건 알아서 판단해도 됨 | GREEN(CORE_INTENT) | 수신자가 자율 결정할 영역(다른 알바). 내용상 실질적 자율권 부여 |

### S9: 동아리부원/피드백, 항의

| Segment | Expected | Note |
|---------|----------|------|
| 행사 망한 건 솔직히 나 혼자 준비해서 그런 것도 있음 | YELLOW(SELF_JUSTIFICATION) | 내용이 부분 인정 + 타인 부족 강조 — 자기변호 구조 |
| 회의 때 다들 조용했잖아 | YELLOW(ACCOUNTABILITY/NEG_FEEDBACK) | 내용이 타인 행동(침묵) 지적 — 귀책 전가 |
| 아이디어 없다고 해서 내가 정한 거고 | YELLOW(SELF_JUSTIFICATION) | 내용이 방어적 정당화 |
| 결과가 별로였다고 나만 탓하는 분위기면 좀 억울함 | YELLOW(EMOTIONAL) | 내용이 불공정함에 대한 감정 표현. 상황 맥락 있으므로 YELLOW |
| 다음 행사부터는 각자 역할 명확히 나눌 거고 | GREEN(CORE_INTENT) | 내용이 건설적 향후 계획 |
| 참여 의지 없는 사람은 빠져도 됨 | YELLOW(NEG_FEEDBACK)/RED(AGGRESSION) | 내용이 "참여 의지 없는"이라는 판단 포함 — 최후통첩 구조. Boundary |

### S10: 의뢰인(디자인)/계약, 거절

| Segment | Expected | Note |
|---------|----------|------|
| 수정이 계속 늘어나는 건 계약 범위를 조금 벗어난 느낌임 | YELLOW(NEG_FEEDBACK)/GREEN(CORE_FACT) | 내용이 범위 초과 관찰 — 사실이나 상대 행동 지적 포함. Boundary |
| 처음엔 "간단한 배너"라 하셨는데...랜딩 전체 구조 바꾸는 수준임 | YELLOW(ACCOUNTABILITY) | 내용이 의뢰인 요구 변화 지적 — 상대 귀책 |
| 저도 다른 프로젝트가 있어서 무한 수정은 어려움 | YELLOW(SELF_JUSTIFICATION) | 내용이 개인 사정으로 거절 근거 제시 — 방어적 프레이밍. Boundary |
| 이번 버전으로 마무리하고 추가 작업은 별도 견적으로 | GREEN(CORE_INTENT) | 내용이 구체적 범위 제안 |
| 계속 이렇게 가면 저도 일정 조정해야 함 | YELLOW(EMOTIONAL)/GREEN(CORE_FACT) | 내용이 실질적 결과 경고(일정 영향). 사실적 맥락 있음. Boundary |

### S11: PT회원/피드백

| Segment | Expected | Note |
|---------|----------|------|
| 체중 안 빠진 건 식단 영향이 큼 | GREEN(CORE_FACT)/YELLOW(ACCOUNTABILITY) | 내용이 전문적 진단이나 암묵적 귀책 포함. Boundary |
| 운동은 제가 시킨 대로 거의 안 하셨고요 | YELLOW(NEGATIVE_FEEDBACK) | 내용이 회원 행동에 대한 직접 판단 — 재구성 필요 |
| 주말에 치팅데이 두 번이면 사실상 유지임 | YELLOW(NEG_FEEDBACK)/GREEN(CORE_FACT) | 내용이 사실 기반 평가이나 판단 포함. Boundary |
| 저도 최선을 다하긴 하는데 | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 노력 강조 — 방어적 구조 |
| 생활 패턴 안 바뀌면 결과 내기 어려움 | GREEN(CORE_FACT)/YELLOW(NEG_FEEDBACK) | 내용이 전문적 조언이나 암묵적 비판 포함. Boundary |
| 다음 달 재등록은 회원님 판단에 맡기겠음 | GREEN(CORE_INTENT) | 수신자가 자율 결정할 영역(재등록 여부). 내용상 실질적 자율권 부여 |

### S12: 임차인/계약, 지원

| Segment | Expected | Note |
|---------|----------|------|
| 집주인이 갑자기 마음 바꿔서 그런 거임 | YELLOW(ACCOUNTABILITY) | 내용이 집주인 귀책 전가 |
| 저희가 속이려고 한 건 아니고요 | YELLOW(SELF_JUSTIFICATION) | 내용이 의도 부인 — 방어적 해명 |
| 요즘 매물 변동이 심해서 어쩔 수 없음 | YELLOW(SELF_JUST)/GREEN(CORE_FACT) | 내용이 시장 상황 설명이나 맥락상 면책 근거로 사용. Boundary |
| 계약금 반환은 절차상 3~5일 걸릴 거고 | GREEN(CORE_FACT) | 내용이 사실 일정 정보 |
| 빨리 받고 싶으시면 집주인 쪽에 직접 연락해보는 게 | GREEN(REQUEST)/YELLOW(NEG_FEEDBACK) | 내용이 실질적 조언(집주인이 환불 주체). 단 맥락상 책임 전가로 읽힐 수 있음. Boundary |

### S13: 클라이언트(IT)/피드백, 지원

| Segment | Expected | Note |
|---------|----------|------|
| 앱이 느린 건 코드가 엉망이라기보단 서버 스펙이 너무 낮아서 | YELLOW(ACCOUNTABILITY) | 내용이 자기 방어 + 클라이언트 측 인프라 귀책 |
| 비용 줄이겠다고 최소 사양으로 가자고 하셨잖음 | YELLOW(ACCOUNTABILITY) | 내용이 클라이언트 과거 결정 지적 — 직접 귀책 |
| 그 조건에서 최적화는 이미 한 상태임 | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 역할 완수 주장 — 방어적 |
| 더 빠르게 하려면 인프라부터 올려야 함 | GREEN(CORE_FACT/REQUEST) | 내용이 기술적 제안 — 문제 해결 방향 |
| 저도 마술은 못 함 | RED(PURE_GRUMBLE/AGGRESSION) | 내용이 빈정거림 — 건설적 내용 없음 |

### S14: 브랜드(협찬)/피드백, 계약

| Segment | Expected | Note |
|---------|----------|------|
| 영상 조회수...알고리즘 영향이 큼 | YELLOW(ACCOUNTABILITY)/GREEN(CORE_FACT) | 내용이 외부 원인(알고리즘) 귀속. 사실이나 방어 목적. Boundary |
| 콘텐츠 퀄리티가 낮아서라기보단...노출이 줄어든 상황임 | YELLOW(SELF_JUSTIFICATION) | 내용이 자기 방어 — 품질 문제 부인 + 외부 원인 제시 |
| 계약상 업로드 의무는 이행했고 | GREEN(CORE_FACT)/YELLOW(SELF_JUST) | 내용이 계약 이행 사실. 방어 목적으로 사용 시 SELF_JUST. Boundary |
| 추가 홍보는 옵션임 | GREEN(CORE_FACT)/YELLOW(NEG_FEEDBACK) | 내용이 계약 범위 명시. 암묵적 추가 노력 거절 포함 |
| 다음 캠페인은 방향을 다시 잡아야 할 듯함 | GREEN(CORE_INTENT) | 내용이 건설적 향후 제안 |
