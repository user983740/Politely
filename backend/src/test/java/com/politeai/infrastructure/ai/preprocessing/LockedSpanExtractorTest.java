package com.politeai.infrastructure.ai.preprocessing;

import com.politeai.domain.transform.model.LockedSpan;
import com.politeai.domain.transform.model.LockedSpanType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockedSpanExtractorTest {

    private LockedSpanExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new LockedSpanExtractor();
    }

    @Nested
    @DisplayName("이메일 추출")
    class EmailTests {
        @Test
        void 기본_이메일() {
            List<LockedSpan> spans = extractor.extract("연락처: test@example.com 입니다.");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("test@example.com");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.EMAIL);
        }

        @Test
        void 특수문자_이메일() {
            List<LockedSpan> spans = extractor.extract("user.name+tag@sub.domain.co.kr");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.EMAIL);
        }
    }

    @Nested
    @DisplayName("URL 추출")
    class UrlTests {
        @Test
        void https_URL() {
            List<LockedSpan> spans = extractor.extract("참고: https://www.example.com/path?q=1 확인해주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("https://www.example.com/path?q=1");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.URL);
        }

        @Test
        void www_URL() {
            List<LockedSpan> spans = extractor.extract("www.example.co.kr 방문해주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.URL);
        }
    }

    @Nested
    @DisplayName("계좌번호 추출")
    class AccountTests {
        @Test
        void 기본_계좌번호() {
            List<LockedSpan> spans = extractor.extract("계좌번호: 110-123-456789 으로 보내주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("110-123-456789");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.ACCOUNT);
        }

        @Test
        void 긴_계좌번호() {
            List<LockedSpan> spans = extractor.extract("3333-12-1234567");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.ACCOUNT);
        }
    }

    @Nested
    @DisplayName("한국어 날짜 추출")
    class DateTests {
        @Test
        void 년월일() {
            List<LockedSpan> spans = extractor.extract("2024년 2월 4일에 만나요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("2024년 2월 4일");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.DATE);
        }

        @Test
        void 월일만() {
            List<LockedSpan> spans = extractor.extract("3월 15일까지 보내주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("3월 15일");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.DATE);
        }

        @Test
        void 년월만() {
            List<LockedSpan> spans = extractor.extract("2024년 12월 마감입니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("2024년 12월");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.DATE);
        }

        @Test
        void 점_구분_날짜() {
            List<LockedSpan> spans = extractor.extract("2024.02.04에 접수함");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("2024.02.04");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.DATE);
        }

        @Test
        void 슬래시_구분_날짜() {
            List<LockedSpan> spans = extractor.extract("2024/02/04 출발");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.DATE);
        }
    }

    @Nested
    @DisplayName("한국어 시간 추출")
    class TimeTests {
        @Test
        void 시간_범위() {
            List<LockedSpan> spans = extractor.extract("12~3시에 방문하겠습니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("12~3시");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.TIME);
        }

        @Test
        void 오후_시간() {
            List<LockedSpan> spans = extractor.extract("오후 2시에 미팅합니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("오후 2시");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.TIME);
        }

        @Test
        void 새벽_시간_범위() {
            List<LockedSpan> spans = extractor.extract("새벽 5~7시에 발생했습니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("새벽 5~7시");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.TIME);
        }

        @Test
        void 시분_형태() {
            List<LockedSpan> spans = extractor.extract("오전 10시 30분에 시작합니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.TIME);
        }
    }

    @Nested
    @DisplayName("HH:MM 시간 추출")
    class HHMMTests {
        @Test
        void 시각() {
            List<LockedSpan> spans = extractor.extract("14:30에 출발합니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("14:30");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.TIME_HH_MM);
        }
    }

    @Nested
    @DisplayName("전화번호 추출")
    class PhoneTests {
        @Test
        void 휴대폰_번호() {
            List<LockedSpan> spans = extractor.extract("연락처: 010-1234-5678 입니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("010-1234-5678");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.PHONE);
        }

        @Test
        void 지역번호_전화() {
            List<LockedSpan> spans = extractor.extract("02-123-4567로 전화주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.PHONE);
        }
    }

    @Nested
    @DisplayName("금액 추출")
    class MoneyTests {
        @Test
        void 만원_단위() {
            List<LockedSpan> spans = extractor.extract("비용은 10만원입니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("10만원");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.MONEY);
        }

        @Test
        void 콤마_포맷_원() {
            List<LockedSpan> spans = extractor.extract("150,000원을 입금해주세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("150,000원");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.MONEY);
        }

        @Test
        void 숫자_원() {
            List<LockedSpan> spans = extractor.extract("5000원짜리");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.MONEY);
        }
    }

    @Nested
    @DisplayName("단위 포함 숫자 추출")
    class UnitNumberTests {
        @Test
        void 자리() {
            List<LockedSpan> spans = extractor.extract("6자리 코드를 입력하세요");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("6자리");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.UNIT_NUMBER);
        }

        @Test
        void 퍼센트() {
            List<LockedSpan> spans = extractor.extract("할인율은 10%입니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).originalText()).isEqualTo("10%");
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.UNIT_NUMBER);
        }

        @Test
        void 개월() {
            List<LockedSpan> spans = extractor.extract("3개월 이내");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.UNIT_NUMBER);
        }
    }

    @Nested
    @DisplayName("큰 숫자 추출")
    class LargeNumberTests {
        @Test
        void 콤마_포맷_숫자() {
            List<LockedSpan> spans = extractor.extract("회원번호 1,234,567 입니다");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.LARGE_NUMBER);
        }

        @Test
        void 다섯자리_이상() {
            List<LockedSpan> spans = extractor.extract("주문번호 12345 확인");
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).type()).isEqualTo(LockedSpanType.LARGE_NUMBER);
        }
    }

    @Nested
    @DisplayName("복합 텍스트")
    class ComplexTests {
        @Test
        void 복합_텍스트에서_여러_스팬_추출() {
            String text = "2024년 2월 4일 12~3시에 150,000원 입금 부탁드립니다. 연락처: 010-1234-5678";
            List<LockedSpan> spans = extractor.extract(text);
            assertThat(spans).hasSizeGreaterThanOrEqualTo(4);

            // Check that all types are represented
            List<LockedSpanType> types = spans.stream().map(LockedSpan::type).toList();
            assertThat(types).contains(LockedSpanType.DATE);
            assertThat(types).contains(LockedSpanType.TIME);
            assertThat(types).contains(LockedSpanType.MONEY);
            assertThat(types).contains(LockedSpanType.PHONE);
        }

        @Test
        void 겹치지_않는_스팬() {
            String text = "test@example.com으로 150,000원 보내주세요";
            List<LockedSpan> spans = extractor.extract(text);

            // Verify no overlapping spans
            for (int i = 0; i < spans.size() - 1; i++) {
                assertThat(spans.get(i).endPos()).isLessThanOrEqualTo(spans.get(i + 1).startPos());
            }
        }

        @Test
        void 빈_텍스트() {
            assertThat(extractor.extract("")).isEmpty();
            assertThat(extractor.extract(null)).isEmpty();
        }

        @Test
        void 스팬_없는_텍스트() {
            assertThat(extractor.extract("안녕하세요 잘 부탁드립니다")).isEmpty();
        }

        @Test
        void 플레이스홀더_인덱스_연속() {
            String text = "2024년 2월 4일 12~3시에 150,000원 입금";
            List<LockedSpan> spans = extractor.extract(text);

            for (int i = 0; i < spans.size(); i++) {
                assertThat(spans.get(i).index()).isEqualTo(i);
                assertThat(spans.get(i).placeholder()).isEqualTo("{{LOCKED_" + i + "}}");
            }
        }
    }
}
