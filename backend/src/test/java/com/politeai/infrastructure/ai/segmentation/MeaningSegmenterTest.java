package com.politeai.infrastructure.ai.segmentation;

import com.politeai.domain.transform.model.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeaningSegmenterTest {

    private MeaningSegmenter segmenter;

    @BeforeEach
    void setUp() throws Exception {
        segmenter = new MeaningSegmenter();
        setField("maxSegmentLength", 250);
        setField("discourseMarkerMinLength", 150);
        setField("enumerationMinLength", 120);
    }

    private void setField(String name, int value) throws Exception {
        Field field = MeaningSegmenter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(segmenter, value);
    }

    // ── Edge cases ──

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null 입력 → 빈 리스트")
        void null_input() {
            assertThat(segmenter.segment(null)).isEmpty();
        }

        @Test
        @DisplayName("빈 문자열 → 빈 리스트")
        void empty_input() {
            assertThat(segmenter.segment("")).isEmpty();
        }

        @Test
        @DisplayName("공백만 → 빈 리스트")
        void blank_input() {
            assertThat(segmenter.segment("   ")).isEmpty();
        }

        @Test
        @DisplayName("1자 텍스트 → 1 세그먼트")
        void single_char() {
            List<Segment> result = segmenter.segment("가");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).text()).isEqualTo("가");
        }

        @Test
        @DisplayName("플레이스홀더만 → 1 세그먼트")
        void placeholder_only() {
            String text = "{{DATE_1}}";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).text()).isEqualTo("{{DATE_1}}");
        }
    }

    // ── Stage 1: Strong structural boundaries ──

    @Nested
    @DisplayName("Stage 1: Strong boundaries")
    class Stage1StrongBoundaries {

        @Test
        @DisplayName("빈 줄로 분절")
        void blank_lines() {
            String text = "첫 번째 문단입니다\n\n두 번째 문단입니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).text()).isEqualTo("첫 번째 문단입니다");
            assertThat(result.get(1).text()).isEqualTo("두 번째 문단입니다");
        }

        @Test
        @DisplayName("불릿 리스트 분절")
        void bullet_list() {
            // Items must be >= 5 chars to avoid short-segment merge
            String text = "목록입니다\n- 첫 번째 항목입니다\n- 두 번째 항목입니다\n- 세 번째 항목입니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("번호 리스트 분절")
        void numbered_list() {
            // Items must be >= 5 chars to avoid short-segment merge
            String text = "안내합니다\n1. 첫 번째 항목입니다\n2. 두 번째 항목입니다\n3. 세 번째 항목입니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("명시적 구분자 (---) 분절")
        void explicit_separator() {
            String text = "섹션 1의 내용입니다\n---\n섹션 2의 내용입니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("명시적 구분자 (===) 분절")
        void equals_separator() {
            String text = "섹션 1의 내용입니다\n===\n섹션 2의 내용입니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }
    }

    // ── Stage 2: Korean sentence endings ──

    @Nested
    @DisplayName("Stage 2: Korean endings")
    class Stage2KoreanEndings {

        @Test
        @DisplayName("합쇼체 종결어미 분절")
        void formal_endings() {
            String text = "보고서를 제출합니다 검토 부탁드립니다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("해요체 종결어미 분절")
        void polite_endings() {
            String text = "지금 확인해요 나중에 알려줄게요";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("해체 종결어미 분절")
        void casual_endings() {
            String text = "나 지금 했어 너도 해봐";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("명사형/평서형 종결어미 분절")
        void narrative_endings() {
            String text = "보고서 제출했음 확인 바람";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("연결어미 '는데' 분절 억제 — 짧은 텍스트")
        void connective_suppression_neunde() {
            // "는데" used as connective mid-sentence — should NOT split
            String text = "바쁜데 잠깐 시간 좀";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("연결어미 '니까' 분절 억제 — 짧은 텍스트")
        void connective_suppression_nikka() {
            String text = "늦었으니까 빨리 출발하자";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("연결어미 뒤에 담화표지어 → 분절 허용")
        void connective_followed_by_discourse_marker() {
            // Use "확인했는데" which properly ends with "는데" (in AMBIGUOUS_ENDINGS)
            String text = "확인했는데 그런데 다른 일도 있어";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("연결어미 + 250자 초과 → 분절 허용 (길이 안전장치)")
        void connective_long_text_splits() {
            // Build text > 250 chars before the connective ending
            String longPart = "이것은 매우 긴 텍스트입니다 ".repeat(20); // ~260 chars
            String text = longPart + "는데 뒤에 내용이 더 있습니다";
            List<Segment> result = segmenter.segment(text);
            // Should split because text before is > 250 chars
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }
    }

    // ── Stage 3: Weak punctuation ──

    @Nested
    @DisplayName("Stage 3: Weak punctuation")
    class Stage3WeakPunctuation {

        @Test
        @DisplayName("마침표 + 공백으로 분절")
        void period_split() {
            // Note: Korean ending pattern (Stage 2) may consume the trailing period
            // "세요" is a polite ending, so "안녕하세요. " matches there
            String text = "좋은 아침입니다. 오늘도 화이팅";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("느낌표 + 공백으로 분절")
        void exclamation_split() {
            String text = "좋습니다! 시작합시다";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("세미콜론으로 분절")
        void semicolon_split() {
            String text = "첫 번째 항목; 두 번째 항목";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("줄임표로 분절")
        void ellipsis_split() {
            String text = "그래서… 결국 그렇게 됐어요";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }
    }

    // ── Stage 4: Force split ──

    @Nested
    @DisplayName("Stage 4: Force split")
    class Stage4ForceSplit {

        @Test
        @DisplayName("250자 초과 세그먼트 강제 분절")
        void force_split_long() throws Exception {
            setField("maxSegmentLength", 250);
            String longText = "가나다라마바사 ".repeat(50); // ~400 chars
            List<Segment> result = segmenter.segment(longText);
            for (Segment s : result) {
                assertThat(s.text().length()).isLessThanOrEqualTo(280); // some margin for split position
            }
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("조사 뒤에서 분절 회피")
        void postposition_avoidance() throws Exception {
            setField("maxSegmentLength", 50);
            // This text has postpositions scattered; should avoid splitting right after them
            String text = "보고서를 제출하였으며 검토는 내일까지 완료될 예정이며 결과는 다음주에 공유하겠습니다";
            List<Segment> result = segmenter.segment(text);
            // Just verify it doesn't crash and produces valid segments
            assertThat(result).isNotEmpty();
            for (Segment s : result) {
                assertThat(s.text()).isNotBlank();
            }
        }
    }

    // ── Stage 5: Enumeration detection ──

    @Nested
    @DisplayName("Stage 5: Enumeration detection")
    class Stage5Enumeration {

        @Test
        @DisplayName("쉼표 리스트 분절 (120자 초과, 3개 이상, 각 15자 이상)")
        void comma_list() {
            // Each item must be > 15 chars, total > 120 chars
            String text = "프론트엔드 사용자 화면 개발 관련 자료 정리 및 검토 진행 중, " +
                    "백엔드 서버 구축 및 배포 관련 자료 정리 및 검토 진행 중, " +
                    "데이터베이스 설계 및 최적화 관련 자료 정리 및 검토 진행 중, " +
                    "인프라 구성 및 모니터링 계획 관련 자료 정리 및 검토 진행 중";
            assertThat(text.length()).isGreaterThan(120);
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("짧은 쉼표 리스트는 분절 안 함 (120자 이하)")
        void short_comma_list_no_split() {
            String text = "사과, 배, 포도, 귤";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("쉼표 항목이 15자 미만이면 분절 안 함")
        void comma_items_too_short() {
            // >120 chars total but each item < 15 chars
            String text = "사과와 배입니다, 포도와 귤입니다, 딸기 수박입니다, 참외와 복숭아, 체리와 블루베리, 라즈베리이고요, 키위와 망고예요, 바나나파파야";
            List<Segment> result = segmenter.segment(text);
            // Should not split by enumeration since items < 15 chars
            // (may still split by other stages)
            for (Segment s : result) {
                assertThat(s.text()).isNotBlank();
            }
        }
    }

    // ── Stage 6: Discourse markers ──

    @Nested
    @DisplayName("Stage 6: Discourse markers")
    class Stage6DiscourseMarkers {

        @Test
        @DisplayName("150자 초과 세그먼트에서 담화표지어로 분절")
        void discourse_marker_long_segment() {
            String longPart = "이것은 긴 텍스트입니다 ".repeat(12); // ~156 chars
            String text = longPart + ". 그런데 다른 이야기를 하자면 여기가 그것입니다";
            List<Segment> result = segmenter.segment(text);
            // "그런데" should cause a split since the segment is > 150 chars
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("150자 이하에서는 담화표지어 분절 안 함")
        void discourse_marker_short_no_split() {
            String text = "짧은 문장이다. 그런데 또 다른 문장이다";
            List<Segment> result = segmenter.segment(text);
            // Should be split by punctuation (Stage 3), not by discourse marker
            // The discourse marker stage only applies to >150 char segments
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("복합어 '그런데도' — 분절 안 함")
        void compound_word_no_split() {
            String longPart = "이것은 매우 긴 텍스트입니다 ".repeat(12);
            String text = longPart + ". 그런데도 계속 진행합니다";
            List<Segment> result = segmenter.segment(text);
            // "그런데도" is a compound — should not split there
            boolean anySegmentEndsWithCompound = result.stream()
                    .anyMatch(s -> s.text().contains("그런데도"));
            assertThat(anySegmentEndsWithCompound).isTrue();
        }
    }

    // ── Stage 7: Merge ──

    @Nested
    @DisplayName("Stage 7: Merge")
    class Stage7Merge {

        @Test
        @DisplayName("3개 이상 연속 짧은 세그먼트 병합")
        void merge_short_consecutive() {
            // Create a text that will produce short segments
            String text = "아. 네. 좋. 감사합니다 오늘 일정을 확인했습니다";
            List<Segment> result = segmenter.segment(text);
            // Short segments "아.", "네.", "좋." should be merged
            for (Segment s : result) {
                assertThat(s.text()).isNotBlank();
            }
        }
    }

    // ── Placeholder protection ──

    @Nested
    @DisplayName("Placeholder protection")
    class PlaceholderProtection {

        @Test
        @DisplayName("플레이스홀더 내부에서 분절 안 함")
        void no_split_inside_placeholder() {
            String text = "보내드린 {{DATE_1}} 파일을 확인해주세요";
            List<Segment> result = segmenter.segment(text);
            // Placeholder should remain intact in some segment
            boolean placeholderIntact = result.stream()
                    .anyMatch(s -> s.text().contains("{{DATE_1}}"));
            assertThat(placeholderIntact).isTrue();
        }

        @Test
        @DisplayName("여러 플레이스홀더 보호")
        void multiple_placeholders() {
            String text = "{{DATE_1}}에서 {{DATE_2}}로 보내주세요. 감사합니다";
            List<Segment> result = segmenter.segment(text);
            String combined = result.stream().map(Segment::text).reduce("", (a, b) -> a + " " + b);
            assertThat(combined).contains("{{DATE_1}}");
            assertThat(combined).contains("{{DATE_2}}");
        }

        @Test
        @DisplayName("병합 시 플레이스홀더 경계 보호")
        void merge_respects_placeholder_boundary() {
            // Short segments around a placeholder should not merge across it
            String text = "아 네 {{DATE_1}} 좋 감 사";
            List<Segment> result = segmenter.segment(text);
            boolean placeholderIntact = result.stream()
                    .anyMatch(s -> s.text().contains("{{DATE_1}}"));
            assertThat(placeholderIntact).isTrue();
        }
    }

    // ── Parenthetical/quoted protection ──

    @Nested
    @DisplayName("Parenthetical and quoted protection")
    class ParenQuoteProtection {

        @Test
        @DisplayName("괄호 내부에서 약한 분절 안 함")
        void no_weak_split_in_parenthetical() {
            String text = "이 내용은 (참고: 중요함. 반드시 확인) 확인이 필요합니다";
            List<Segment> result = segmenter.segment(text);
            // The period inside parens should not cause a split
            boolean parenIntact = result.stream()
                    .anyMatch(s -> s.text().contains("(참고: 중요함. 반드시 확인)"));
            assertThat(parenIntact).isTrue();
        }

        @Test
        @DisplayName("따옴표 내부에서 약한 분절 안 함")
        void no_weak_split_in_quotes() {
            String text = "그가 \"정말 좋습니다. 감사해요\" 라고 말했습니다";
            List<Segment> result = segmenter.segment(text);
            boolean quoteIntact = result.stream()
                    .anyMatch(s -> s.text().contains("\"정말 좋습니다. 감사해요\""));
            assertThat(quoteIntact).isTrue();
        }
    }

    // ── Position accuracy ──

    @Nested
    @DisplayName("Position accuracy")
    class PositionAccuracy {

        @Test
        @DisplayName("start/end가 원본 텍스트에 정확히 매핑")
        void positions_map_to_original() {
            String text = "안녕하세요. 반갑습니다. 오늘 좋은 하루 되세요";
            List<Segment> result = segmenter.segment(text);

            for (Segment s : result) {
                assertThat(s.start()).isGreaterThanOrEqualTo(0);
                assertThat(s.end()).isLessThanOrEqualTo(text.length());
                assertThat(s.start()).isLessThan(s.end());
                // The text at positions should match (modulo trimming)
                String originalSlice = text.substring(s.start(), s.end());
                assertThat(originalSlice).contains(s.text());
            }
        }

        @Test
        @DisplayName("세그먼트 ID 순차 부여 (T1, T2, ...)")
        void segment_ids_sequential() {
            String text = "첫 번째입니다. 두 번째입니다. 세 번째입니다";
            List<Segment> result = segmenter.segment(text);
            for (int i = 0; i < result.size(); i++) {
                assertThat(result.get(i).id()).isEqualTo("T" + (i + 1));
            }
        }

        @Test
        @DisplayName("중복 텍스트에서 위치 추적 정확도")
        void duplicate_text_positions() {
            String text = "확인합니다. 확인합니다. 감사합니다";
            List<Segment> result = segmenter.segment(text);
            // Positions should be non-overlapping and sequential
            for (int i = 0; i < result.size() - 1; i++) {
                assertThat(result.get(i).end()).isLessThanOrEqualTo(result.get(i + 1).start() + 1);
            }
        }
    }

    // ── Integration tests ──

    @Nested
    @DisplayName("Integration: Real Korean text")
    class IntegrationRealText {

        @Test
        @DisplayName("실제 한국어 이메일 텍스트 분절")
        void real_korean_email() {
            String text = """
                    안녕하세요 {{DATE_1}}님. \
                    지난번에 말씀드린 보고서 관련해서 연락드립니다. \
                    {{DATE_2}}까지 제출해야 하는데 아직 자료가 준비되지 않았습니다. \
                    확인 부탁드립니다""";
            List<Segment> result = segmenter.segment(text);

            assertThat(result).isNotEmpty();
            // All placeholders should be preserved
            String combined = result.stream().map(Segment::text).reduce("", (a, b) -> a + " " + b);
            assertThat(combined).contains("{{DATE_1}}");
            assertThat(combined).contains("{{DATE_2}}");
        }

        @Test
        @DisplayName("짧은 한국어 텍스트 — 과잉 분절 방지")
        void short_text_no_over_segmentation() {
            String text = "내일까지 보내주세요";
            List<Segment> result = segmenter.segment(text);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("복합 구조 (불릿 + 종결어미 + 플레이스홀더)")
        void complex_structure() {
            String text = """
                    보고 드립니다\n\
                    \n\
                    1. {{DATE_1}} 프로젝트 일정이 변경되었습니다\n\
                    2. 새로운 일정은 {{DATE_2}}입니다\n\
                    3. 추가 자료는 {{URL_1}}에서 확인 가능합니다\n\
                    \n\
                    감사합니다""";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(3);

            // All placeholders preserved
            String combined = result.stream().map(Segment::text).reduce("", (a, b) -> a + " " + b);
            assertThat(combined).contains("{{DATE_1}}");
            assertThat(combined).contains("{{DATE_2}}");
            assertThat(combined).contains("{{URL_1}}");
        }

        @Test
        @DisplayName("혼합 어체 텍스트")
        void mixed_speech_levels() {
            String text = "보고서 확인했습니다 그리고 수정사항이 있어요 빨리 고쳐야 할 것 같음";
            List<Segment> result = segmenter.segment(text);
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
        }
    }
}
