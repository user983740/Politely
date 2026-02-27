# Test Scenarios

14 predefined scenarios shared by test-labeling and test-transform skills.

## S1: 학부모/피드백

```json
{"persona":"PARENT","contexts":["FEEDBACK"],"toneLevel":"POLITE","originalText":"안녕하쇼 담임임. 님 애가 시험을 망해서 놀라셨죠. 근데 내 탓하려고 시동걸거 같아서 말해두는데 난 숙제도 제대로 내주고 진도 따라서 스터디 플랜도 잘 짜줬다. 근데 님 애가 안 쳐한거임. 학원 바꿀꺼면 머 ㅃㅃ인데 못바꾸잔아 걍 다음에 잘 보고 싶으면 님도 애를 좀 잡아봐 숙제를 하게."}
```

## S2: 직장상사/사과, 항의

```json
{"persona":"BOSS","contexts":["APOLOGY","COMPLAINT"],"toneLevel":"POLITE","originalText":"솔직히 말해서 이번 일정 또 밀린거 저 때문만은 아니고 PR-482도 아직 안 닫혔고 김민수도 답을 안 줬고 그래서 v2.3.1 배포가 지금 이 꼴 난건데 아무튼 제가 다 책임져야 하는 분위기라 좀 억울하긴 합니다 근데 어쨌든 오늘 18:00 전에 report_final_v3.docx는 올리긴 올릴거고 링크는 https://drive.company.com/alpha 이거고 제 이메일은 alpha.team.lead@company.co.kr 인데 사실 어제도 새벽 3시까지 했고 허리도 아프고 이거 계속 이런식이면 저도 진짜 모르겠습니다 일단 오늘은 넘기고 나중에 구조 다시 갈아엎든지 하죠"}
```

## S3: 고객/요청, 피드백

```json
{"persona":"CLIENT","contexts":["REQUEST","FEEDBACK"],"toneLevel":"POLITE","originalText":"고객님 솔직히 말씀드리면 이번 오류는 저희 쪽 문제라기보단 귀사 서버 설정이 이상해서 생긴거고 제가 지난번에도 config.yaml이랑 MainService.java 건드리지 말라고 했는데 또 수정하셔서 이 사단이 난거 같습니다 아무튼 현재 010-4921-8823으로 연락주셔도 되고 계좌는 110-123-456789이고 환불 얘기는 좀 과한 것 같고 일단 로그파일 error_dump_2026_02_14.zip 보내주시면 제가 보겠습니다 근데 저도 사람이라 하루종일 이런 대응하면 멘탈 좀 나가긴 합니다"}
```

## S4: 교수/사과

```json
{"persona":"PROFESSOR","contexts":["APOLOGY"],"toneLevel":"POLITE","originalText":"교수님 이번 과제 제출이 늦어진건 사실 제 잘못도 있지만 JIRA-991 이 계속 꼬여서 그런거고 사실 개인적으로도 집안일이 좀 있었고 그래서 정신이 없었습니다 그래도 결과물은 final_thesis_v1.0.4.pdf로 정리해놨고 https://snu-project-lab.com/upload 여기 업로드 예정인데 솔직히 이런식으로 일정이 빡빡하면 연구 퀄리티가 떨어질 수밖에 없다고 생각합니다 UUID는 123e4567-e89b-12d3-a456-426614174000 이고 어쨌든 늦은건 죄송하지만 저만 탓받는 느낌은 좀 억울하네요"}
```

## S5: 고객(환불)/결제, 항의

```json
{"persona":"CLIENT","contexts":["BILLING","COMPLAINT"],"toneLevel":"POLITE","originalText":"이번 장애가 불편하셨던 건 이해함. 근데 솔직히 SLA 범위 벗어난 건 아니고 약관에도 명시되어 있음. 저희도 어제 새벽 2시까지 복구 작업했고요. 전액 환불은 좀 과한 요구 같고 부분 크레딧 정도는 내부 검토해볼 수 있음. 계속 이런 식으로 강하게 나오시면 저도 대응이 어렵습니다."}
```

## S6: 조원(팀프로젝트)/독촉, 피드백

```json
{"persona":"OTHER","contexts":["URGING","FEEDBACK"],"toneLevel":"POLITE","originalText":"솔직히 이번 발표 망하면 우리 다 점수 날아가는데 준비 상황 보면 좀 답답함. 내가 슬라이드 거의 다 만들고 있는데 최소한 스크립트는 알아서 써와야 하는 거 아닌가 싶음. 나도 시험 기간이라 여유 없음. 오늘 밤까지 초안 안 주면 그냥 내가 임의로 정리해서 이름만 올릴게."}
```

## S7: 수강생(온라인)/지원, 피드백

```json
{"persona":"OTHER","contexts":["SUPPORT","FEEDBACK"],"toneLevel":"POLITE","originalText":"과제 제출 안 된 건 시스템 오류라기보단 브라우저 설정 문제 같음. 저희 쪽 서버 로그에는 submit 기록이 없음. 매뉴얼에도 크롬 최신 버전 쓰라고 적어놨고요. 일단 마감은 이미 지났지만 사정은 이해하니 오늘 자정까지는 다시 열어두겠음. 다만 이런 요청 계속 들어오면 운영이 좀 힘듦."}
```

## S8: 알바직원/일정지연, 공지

```json
{"persona":"OTHER","contexts":["SCHEDULE_DELAY","ANNOUNCEMENT"],"toneLevel":"POLITE","originalText":"이번 주 스케줄 안 나온 건 내가 일부러 늦게 준 게 아니라 본사에서 아직 확정을 안 줘서 그런 거임. 나도 계속 전화 돌리고 있음. 근데 단톡방에 자꾸 언제 나오냐고 재촉하면 나도 난감함. 일단 금요일까지는 줄 건데, 그 전에 다른 알바 잡는 건 알아서 판단해도 됨."}
```

## S9: 동아리부원/피드백, 항의

```json
{"persona":"OTHER","contexts":["FEEDBACK","COMPLAINT"],"toneLevel":"POLITE","originalText":"행사 망한 건 솔직히 나 혼자 준비해서 그런 것도 있음. 회의 때 다들 조용했잖아. 아이디어 없다고 해서 내가 정한 거고. 결과가 별로였다고 나만 탓하는 분위기면 좀 억울함. 다음 행사부터는 각자 역할 명확히 나눌 거고, 참여 의지 없는 사람은 빠져도 됨."}
```

## S10: 의뢰인(디자인)/계약, 거절

```json
{"persona":"CLIENT","contexts":["CONTRACT","REJECTION"],"toneLevel":"POLITE","originalText":"수정이 계속 늘어나는 건 계약 범위를 조금 벗어난 느낌임. 처음엔 \"간단한 배너\"라 하셨는데 지금은 랜딩 전체 구조 바꾸는 수준임. 저도 다른 프로젝트가 있어서 무한 수정은 어려움. 일단 이번 버전으로 마무리하고 추가 작업은 별도 견적으로 가는 게 맞을 듯함. 계속 이렇게 가면 저도 일정 조정해야 함."}
```

## S11: PT회원/피드백

```json
{"persona":"CLIENT","contexts":["FEEDBACK"],"toneLevel":"POLITE","originalText":"체중 안 빠진 건 식단 영향이 큼. 운동은 제가 시킨 대로 거의 안 하셨고요. 주말에 치팅데이 두 번이면 사실상 유지임. 저도 최선을 다하긴 하는데 생활 패턴 안 바뀌면 결과 내기 어려움. 다음 달 재등록은 회원님 판단에 맡기겠음."}
```

## S12: 임차인/계약, 지원

```json
{"persona":"CLIENT","contexts":["CONTRACT","SUPPORT"],"toneLevel":"POLITE","originalText":"계약 취소된 건 집주인이 갑자기 마음 바꿔서 그런 거임. 저희가 속이려고 한 건 아니고요. 요즘 매물 변동이 심해서 어쩔 수 없음. 계약금 반환은 절차상 3~5일 걸릴 거고, 빨리 받고 싶으시면 집주인 쪽에 직접 연락해보는 게 나을 수도 있음."}
```

## S13: 클라이언트(IT)/피드백, 지원

```json
{"persona":"CLIENT","contexts":["FEEDBACK","SUPPORT"],"toneLevel":"POLITE","originalText":"지금 앱이 느린 건 코드가 엉망이라기보단 서버 스펙이 너무 낮아서 그런 거임. 처음에 비용 줄이겠다고 최소 사양으로 가자고 하셨잖음. 그 조건에서 최적화는 이미 한 상태임. 더 빠르게 하려면 인프라부터 올려야 함. 저도 마술은 못 함."}
```

## S14: 브랜드(협찬)/피드백, 계약

```json
{"persona":"CLIENT","contexts":["FEEDBACK","CONTRACT"],"toneLevel":"POLITE","originalText":"영상 조회수 기대만큼 안 나온 건 알고리즘 영향이 큼. 콘텐츠 퀄리티가 낮아서라기보단 최근 채널 전체 노출이 줄어든 상황임. 계약상 업로드 의무는 이행했고, 추가 홍보는 옵션임. 다음 캠페인은 방향을 다시 잡아야 할 듯함."}
```
