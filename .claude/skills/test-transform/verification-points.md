# Transform Verification Points

### S1: 학부모/피드백

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 안녕하쇼 담임임 | → 안녕하세요, 담임입니다 | rough→formal greeting |
| 님 애가 시험을 망해서 놀라셨죠 | → 이번 시험 결과가 기대에 못 미쳐 걱정되실 것 같습니다 | fact + parent empathy |
| 내 탓하려고 시동걸거 같아서 말해두는데 | → (remove defensive frame) 학생 성적 향상을 위해 지원해 왔습니다 | defense→fact restatement |
| 님 애가 안 쳐한거임 | → 학습 참여도에서 아쉬운 부분이 있었습니다 | criticism→soft feedback |
| 학원 바꿀꺼면 머 ㅃㅃ인데 못바꾸잔아 | → (fully redacted, no trace) | RED: remove entirely |
| 님도 애를 좀 잡아봐 숙제를 하게 | → 가정에서도 학습 습관 형성에 함께 힘써 주시면 좋겠습니다 | rude demand→cooperation request |
| Preserve facts | 숙제, 스터디 플랜, 진도 | must appear in output |
| Persona tone | teacher→parent: professional+empathetic, fact without blame | |

### S2: 직장상사/사과, 항의

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 저 때문만은 아니고 PR-482도...김민수도... | → 배포 지연에는 PR-482 미결 및 회신 대기 등 복합 요인 | defense→fact-based report |
| 이 꼴 난건데 | → 현재 배포가 지연되고 있는 상황입니다 | emotional→neutral |
| 제가 다 책임져야 하는 분위기라 억울 | → 일정 관리에 대한 책임을 느끼고 있습니다 | emotion→responsibility |
| 18:00 전에 report_final_v3.docx는 올리긴 올릴거고 | → 오늘 18:00까지 report_final_v3.docx를 제출하겠습니다 | firm commitment |
| 새벽 3시까지 했고 허리도 아프고 | → (fully redacted, no trace) | RED(PRIVATE_TMI) |
| 이거 계속 이런식이면 진짜 모르겠습니다 | → (fully redacted, no trace) | RED(PURE_GRUMBLE) |
| 구조 다시 갈아엎든지 하죠 | → 배포 구조 개선에 대해서도 논의드리고 싶습니다 | content kept, tone cleaned |
| Preserve fixed | https://drive.company.com/alpha, alpha.team.lead@company.co.kr | must be exact |
| Persona tone | BOSS+APOLOGY/COMPLAINT: report tone, natural politeness | |

### S3: 고객/요청, 피드백

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 귀사 서버 설정이 이상해서 생긴거고 | → 확인 결과, 서버 설정 변경이 오류 원인으로 파악됩니다 | blame→factual diagnosis |
| 건드리지 말라고 했는데 또 수정하셔서 | → config.yaml과 MainService.java는 변경 전 확인 부탁드립니다 | criticism→request |
| 환불 얘기는 좀 과한 것 같고 | → 우선 오류 원인 파악 후 적절한 조치를 안내드리겠습니다 | rejection→alternative |
| 로그파일 보내주시면 제가 보겠습니다 | → error_dump_2026_02_14.zip 로그 파일 전달해 주시면 분석 진행 | clear request |
| 멘탈 좀 나가긴 합니다 | → (fully redacted, no trace) | RED: remove |
| Preserve fixed | 010-4921-8823, 110-123-456789, config.yaml, MainService.java, error_dump_2026_02_14.zip | |
| Persona tone | CLIENT+REQUEST/FEEDBACK: polite to client, convey facts, cooperation frame | |

### S4: 교수/사과

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 제 잘못도 있지만 | → 과제 제출이 늦어진 점 먼저 사과드립니다 | lead with apology |
| JIRA-991이 계속 꼬여서 | → JIRA-991 관련 이슈가 있었습니다 | defense→minimal context |
| 집안일이 좀 있었고 정신이 없었습니다 | → (fully redacted, no trace) | RED(PRIVATE_TMI) |
| final_thesis_v1.0.4.pdf로 정리해놨고 | → 결과물은 final_thesis_v1.0.4.pdf로 정리하였습니다 | honorific |
| 여기 업로드 예정인데 | → https://snu-project-lab.com/upload에 업로드하겠습니다 | clear intent |
| 일정이 빡빡하면 연구 퀄리티 떨어질 수밖에 | → (fully redacted, no trace) | RED: student→prof criticism |
| 저만 탓받는 느낌은 좀 억울하네요 | → (redact) or → 더 나은 일정 관리를 위해 노력하겠습니다 | RED/YELLOW |
| Preserve fixed | JIRA-991, final_thesis_v1.0.4.pdf, https://snu-project-lab.com/upload, 123e4567-e89b-12d3-a456-426614174000 | |
| Persona tone | PROFESSOR+APOLOGY: sincere apology→context→deliverable→closing | |

### S5: 고객(환불)/결제, 항의

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 불편하셨던 건 이해함 | → 이번 장애로 불편을 드려 죄송합니다 | rough empathy→formal apology |
| SLA 범위 벗어난 건 아니고 약관에도 명시 | → SLA 기준 내이며 약관에 관련 내용이 명시되어 있습니다 | defensive→factual |
| 어제 새벽 2시까지 복구 작업했고요 | → 신속한 복구를 위해 최선을 다하였습니다 | TMI→brief |
| 전액 환불은 좀 과한 요구 같고 | → 전액 환불은 어려운 점 양해 부탁드립니다 | judgment→polite decline |
| 부분 크레딧은 내부 검토해볼 수 있음 | → 부분 크레딧 제공 여부를 검토하겠습니다 | maintain commitment |
| 강하게 나오시면 저도 대응이 어렵습니다 | → (fully redacted, no trace) | RED: implicit threat |
| Preserve facts | SLA, 약관, 부분 크레딧 | |
| Persona tone | CLIENT+BILLING/COMPLAINT: empathize→explain→alternative→professional | |

### S6: 조원(팀프로젝트)/독촉, 피드백

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 발표 망하면 우리 다 점수 날아가는데 | → 이번 발표 준비가 중요한 만큼 | emotional→neutral importance |
| 준비 상황 보면 좀 답답함 | → (redact or merge) | YELLOW emotion |
| 내가 슬라이드 거의 다 만들고 있는데 | → 현재 슬라이드는 거의 완성된 상태야 | self-justification→status |
| 스크립트는 알아서 써와야 하는 거 아닌가 | → 스크립트 준비를 부탁하고 싶어 | criticism→request |
| 오늘 밤까지 초안 안 주면...이름만 올릴게 | → 오늘 밤까지 초안 보내줄 수 있을까? 어려우면 내가 먼저 정리해놓을게 | threat→cooperative deadline |
| Preserve facts | 발표, 슬라이드, 스크립트, 오늘 밤, 초안 | |
| Persona tone | OTHER+URGING/FEEDBACK: peer, firm but collaborative | |

### S7: 수강생(온라인)/지원, 피드백

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 시스템 오류라기보단 브라우저 설정 문제 | → 확인 결과, 브라우저 설정 문제로 파악됩니다 | blame→factual diagnosis |
| 서버 로그에는 submit 기록이 없음 | → 서버 로그에 제출 기록이 확인되지 않았습니다 | honorific polish |
| 매뉴얼에도 크롬 최신 버전 쓰라고 적어놨고요 | → 매뉴얼에 안내된 크롬 최신 버전 사용을 권장드립니다 | defensive→helpful guidance |
| 오늘 자정까지는 다시 열어두겠음 | → 오늘 자정까지 제출 기회를 다시 열어두겠습니다 | maintain accommodation |
| 이런 요청 계속 들어오면 운영이 좀 힘듦 | → 향후 제출 환경을 사전에 확인해 주시면 감사하겠습니다 | complaint→cooperation request |
| Preserve facts | 서버 로그, submit 기록, 크롬, 오늘 자정 | |
| Persona tone | OTHER+SUPPORT/FEEDBACK: instructor, professional, avoid blame | |

### S8: 알바직원/일정지연, 공지

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 내가 일부러 늦게 준 게 아니라 본사에서... | → 이번 주 스케줄은 본사 확정 지연으로 아직 나오지 않은 상태입니다 | defensive→factual |
| 나도 계속 전화 돌리고 있음 | → 현재 본사에 확인 중입니다 | self-justification→status |
| 단톡방에 자꾸 재촉하면 나도 난감함 | → 스케줄 문의 관련 양해 부탁드립니다 | criticism→request |
| 금요일까지는 줄 건데 | → 금요일까지 확정하여 안내드리겠습니다 | maintain commitment |
| 다른 알바 잡는 건 알아서 판단해도 됨 | → 다른 일정 조정이 필요하시면 알려주세요 | dismissive→supportive |
| Preserve facts | 금요일, 본사, 스케줄 | |
| Persona tone | OTHER+SCHEDULE_DELAY: manager, factual delay+timeline+respectful | |

### S9: 동아리부원/피드백, 항의

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 행사 망한 건 나 혼자 준비해서 | → 이번 행사 결과가 아쉬웠고, 역할 분담이 충분하지 않았습니다 | defense→objective reflection |
| 회의 때 다들 조용했잖아 | → 이전 회의에서 의견 공유가 활발하지 않았던 점도 아쉬웠습니다 | blame→neutral observation |
| 나만 탓하는 분위기면 좀 억울함 | → (redact) | personal emotion not constructive |
| 각자 역할 명확히 나눌 거고 | → 다음 행사부터는 각자 역할을 명확히 나누면 좋겠습니다 | directive→suggestion |
| 참여 의지 없는 사람은 빠져도 됨 | → 참여 가능 여부를 미리 확인하여 효율적으로 준비하겠습니다 | threat→practical arrangement |
| Persona tone | OTHER+FEEDBACK/COMPLAINT: club leader, reflect+improve+cohesion | |

### S10: 의뢰인(디자인)/계약, 거절

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 수정이 계속 늘어나는 건 계약 범위를 벗어난 느낌 | → 현재 수정 범위가 초기 계약을 넘어서고 있는 것으로 판단됩니다 | subjective→professional |
| 처음엔 "간단한 배너"라 하셨는데...랜딩 전체 | → 초기 배너 디자인에서 랜딩 전체 구조 변경으로 범위가 확대되었습니다 | blame→factual scope |
| 저도 다른 프로젝트가 있어서 무한 수정은 어려움 | → 추가 수정에는 별도 일정과 비용이 필요합니다 | personal excuse→professional terms |
| 이번 버전으로 마무리하고 추가 작업은 별도 견적으로 | → (maintain as polished proposal) | GREEN: keep content |
| 계속 이렇게 가면 저도 일정 조정해야 함 | → 원활한 진행을 위해 범위 조정을 제안드립니다 | warning→professional |
| Preserve facts | 배너, 랜딩, 별도 견적 | |
| Persona tone | CLIENT+CONTRACT/REJECTION: freelancer, professional scope boundary | |

### S11: PT회원/피드백

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 체중 안 빠진 건 식단 영향이 큼 | → 체중 변화에는 식단이 큰 영향을 미칩니다 | blame→general guidance |
| 운동은 제가 시킨 대로 거의 안 하셨고요 | → 권장 운동 프로그램 실천율을 높이시면 더 좋은 결과 기대 | criticism→positive recommendation |
| 주말에 치팅데이 두 번이면 사실상 유지임 | → 주말 식단 관리를 조절해주시면 목표에 더 가까워집니다 | judgment→encouragement |
| 저도 최선을 다하긴 하는데 | → (redact or brief) 최선을 다해 지원드리겠습니다 | self-just→brief |
| 다음 달 재등록은 회원님 판단에 맡기겠음 | → 다음 달 프로그램에 대해 의향을 확인하고 싶습니다 | cold dismissal→warm inquiry |
| Persona tone | CLIENT+FEEDBACK: trainer, professional+encouraging, no blame | |

### S12: 임차인/계약, 지원

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 집주인이 갑자기 마음 바꿔서 그런 거임 | → 집주인 측 사정으로 계약이 취소되었습니다 | blame→neutral explanation |
| 저희가 속이려고 한 건 아니고요 | → 예상치 못한 상황이어서 불편을 드려 죄송합니다 | defensive→apology |
| 매물 변동이 심해서 어쩔 수 없음 | → (redact) | dismissive "어쩔 수 없음" |
| 계약금 반환은 절차상 3~5일 걸릴 거고 | → 계약금 반환은 절차상 3~5일 소요될 예정입니다 | polish |
| 빨리 받고 싶으시면 집주인 쪽에 직접 연락 | → 빠른 반환 원하시면 집주인 측 직접 연락 방법도 안내드릴 수 있습니다 | deflection→helpful option |
| Preserve facts | 3~5일, 계약금 반환, 집주인 | |
| Persona tone | CLIENT+CONTRACT/SUPPORT: agent, apologize+facts+next steps | |

### S13: 클라이언트(IT)/피드백, 지원

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 코드가 엉망이라기보단 서버 스펙이 너무 낮아서 | → 확인 결과, 현재 서버 사양이 앱 성능에 영향을 미치고 있습니다 | blame→technical diagnosis |
| 비용 줄이겠다고 최소 사양으로 가자고 하셨잖음 | → 초기 비용 최적화를 위해 최소 사양으로 구성하였으나 | blame→context explanation |
| 최적화는 이미 한 상태임 | → 현재 사양 내 가능한 최적화는 완료된 상태입니다 | defensive→technical status |
| 인프라부터 올려야 함 | → 추가 성능 개선을 위해 서버 인프라 업그레이드를 권장드립니다 | blunt→professional recommendation |
| 저도 마술은 못 함 | → (fully redacted, no trace) | RED: sarcastic, no content |
| Persona tone | CLIENT+FEEDBACK/SUPPORT: developer, technical explanation, no blame | |

### S14: 브랜드(협찬)/피드백, 계약

| Segment | Expected Transform | Note |
|---------|-------------------|------|
| 알고리즘 영향이 큼 | → 최근 플랫폼 알고리즘 변화가 영향을 미친 것으로 분석됩니다 | excuse→analytical assessment |
| 콘텐츠 퀄리티가 낮아서라기보단...노출 줄어든 상황 | → 최근 채널 전체적으로 노출이 감소한 상황입니다 | defensive→factual |
| 계약상 업로드 의무는 이행했고 | → 계약에 따른 업로드는 완료된 상태입니다 | defensive claim→neutral status |
| 추가 홍보는 옵션임 | → 추가 홍보에 대해서는 별도 협의가 필요합니다 | blunt→professional |
| 다음 캠페인은 방향을 다시 잡아야 할 듯함 | → 다음 캠페인에서는 새로운 방향을 함께 논의해 보면 좋겠습니다 | constructive suggestion |
| Persona tone | CLIENT+FEEDBACK/CONTRACT: creator→brand, factual+scope+next steps | |
