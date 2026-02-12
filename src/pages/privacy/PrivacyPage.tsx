import { Layout } from '@/shared/ui';

export default function PrivacyPage() {
  return (
    <Layout>
      <title>개인정보 처리방침 - Politely</title>
      <meta name="description" content="Politely 개인정보 처리방침. 이용자의 개인정보 보호를 위한 정책을 안내합니다." />
      <link rel="canonical" href="https://politely-ai.com/privacy" />
      <article className="max-w-2xl mx-auto px-4 py-8 sm:py-12">
        <h1 className="text-2xl font-bold text-text mb-8">개인정보 처리방침</h1>

        <div className="prose prose-sm text-text space-y-6 text-sm leading-relaxed">
          <p>
            PoliteAi(이하 "서비스")는 「개인정보 보호법」에 따라 이용자의 개인정보를 보호하고,
            이와 관련한 고충을 신속하게 처리하기 위하여 다음과 같이 개인정보 처리방침을 수립·공개합니다.
          </p>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">1. 수집하는 개인정보 항목</h2>
            <p>서비스는 회원가입 및 서비스 이용을 위해 아래와 같은 개인정보를 수집합니다.</p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li><strong>필수 항목:</strong> 이메일 주소, 아이디, 이름, 비밀번호(암호화 저장)</li>
              <li><strong>자동 수집 항목:</strong> 서비스 이용 기록, 접속 로그, 접속 IP</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">2. 개인정보의 이용 목적</h2>
            <p>수집한 개인정보는 다음의 목적으로 이용됩니다.</p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li>회원 가입 및 관리: 회원제 서비스 이용에 따른 본인 확인, 개인 식별, 부정 이용 방지</li>
              <li>서비스 제공: 텍스트 톤 변환 서비스 제공, 서비스 개선을 위한 통계 분석</li>
              <li>고충 처리: 이용자 문의 대응, 공지사항 전달</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">3. 개인정보의 보유 및 이용 기간</h2>
            <p>
              이용자의 개인정보는 원칙적으로 개인정보의 수집 및 이용 목적이 달성되면 지체 없이 파기합니다.
              단, 관련 법령에 따라 보존할 필요가 있는 경우 해당 법령에서 정한 기간 동안 보관합니다.
            </p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li>회원 탈퇴 시: 즉시 파기</li>
              <li>전자상거래 등에서의 소비자 보호에 관한 법률에 따른 계약 또는 청약철회 등에 관한 기록: 5년</li>
              <li>통신비밀보호법에 따른 로그인 기록: 3개월</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">4. 개인정보의 제3자 제공</h2>
            <p>
              서비스는 원칙적으로 이용자의 개인정보를 제3자에게 제공하지 않습니다.
              다만, 다음의 경우에는 예외로 합니다.
            </p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li>이용자가 사전에 동의한 경우</li>
              <li>법령에 의해 요구되는 경우</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">5. 개인정보 처리 위탁</h2>
            <p>서비스는 원활한 서비스 제공을 위해 다음과 같이 개인정보 처리를 위탁하고 있습니다.</p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li><strong>Amazon Web Services (AWS):</strong> 클라우드 서버 운영 및 데이터 저장</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">6. 이용자의 권리와 행사 방법</h2>
            <p>이용자는 언제든지 다음의 권리를 행사할 수 있습니다.</p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li>개인정보 열람 요구</li>
              <li>개인정보 정정·삭제 요구</li>
              <li>개인정보 처리 정지 요구</li>
              <li>회원 탈퇴</li>
            </ul>
            <p className="mt-2">
              위 권리 행사는 서비스 내 설정 또는 이메일을 통해 요청할 수 있으며,
              서비스는 지체 없이 필요한 조치를 취하겠습니다.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">7. 개인정보의 안전성 확보 조치</h2>
            <p>서비스는 개인정보의 안전성 확보를 위해 다음과 같은 조치를 취하고 있습니다.</p>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li>비밀번호의 암호화 저장 (BCrypt)</li>
              <li>전송 구간 암호화 (HTTPS/TLS)</li>
              <li>접근 권한 제한 및 관리</li>
              <li>개인정보 처리 시스템의 접근 기록 보관</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">8. 개인정보 보호책임자</h2>
            <ul className="list-disc pl-5 space-y-1 mt-2">
              <li><strong>담당자:</strong> PoliteAi 운영팀</li>
              <li><strong>이메일:</strong> privacy@politeai.com</li>
            </ul>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-text mt-6 mb-3">9. 개인정보 처리방침 변경</h2>
            <p>
              이 개인정보 처리방침은 법령, 정책 또는 서비스 변경에 따라 내용이 변경될 수 있습니다.
              변경 시 서비스 공지사항을 통해 안내하겠습니다.
            </p>
          </section>

          <p className="text-text-secondary mt-8">
            시행일: 2026년 2월 9일
          </p>
        </div>
      </article>
    </Layout>
  );
}
