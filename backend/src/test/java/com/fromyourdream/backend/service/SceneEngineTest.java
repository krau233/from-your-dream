package com.fromyourdream.backend.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SceneEngineTest {

    private static final List<String> BOTH = List.of("lin-che", "xu-zhi");
    private static final Map<String, String> NAMES = Map.of("lin-che", "林澈", "xu-zhi", "许栀");
    private static final Map<String, String> MEMORIES = Map.of("lin-che", "听见本轮交谈。", "xu-zhi", "听见本轮交谈。");
    private CharacterAgent agent;

    @BeforeEach
    void setup() {
        agent = mock(CharacterAgent.class);
    }

    @Test
    void environmentAndPresenceAreUpdatedWithoutEndingTheMilestone() {
        var engine = engine();
        var initial = engine.initialState();
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走到门外接水。", "林澈留在琴边，教室里安静下来。", List.of("lin-che"), MEMORIES,
                false, "", "", false, ""));
        var result = resolve(engine, initial, 1, MEMORIES);
        assertEquals("practice", result.scene().id());
        assertEquals(List.of("lin-che"), result.scene().presentCharacterIds());
        assertEquals("林澈留在琴边，教室里安静下来。", result.scene().environment());
        assertEquals(1, result.scene().turns());
        assertFalse(result.milestoneEnded());
        assertFalse(result.scene().ended());
        assertEquals(BOTH, initial.presentCharacterIds());
    }

    @Test
    void responderPlanCanBeEmptyButCannotNameAbsentOrDuplicateCharacters() {
        var engine = engine();
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of()));
        assertTrue(engine.responders(engine.initialState(), engine.initialOutline(), "听琴", "安静一会儿").isEmpty());
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("unknown")));
        assertInvalid(() -> engine.responders(engine.initialState(), engine.initialOutline(), "公开场景", "你好"));
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("lin-che", "lin-che")));
        assertInvalid(() -> engine.responders(engine.initialState(), engine.initialOutline(), "公开场景", "你好"));
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("xu-zhi")));
        var aloneWithLin = new SceneEngine.State("practice", "排练", "许栀不在。", List.of("lin-che"), 0, false);
        assertInvalid(() -> engine.responders(aloneWithLin, engine.initialOutline(), "仅林澈在场", "你好"));
    }

    @Test
    void milestoneClosureUsesAValidatedRevisedPlan() {
        var engine = engine();
        when(agent.advance(anyString(), anyString())).thenReturn(beat(true));
        when(agent.reviseOutline(anyString(), anyString())).thenReturn(outline(List.of(plan("practice", "重新排练"))));
        assertInvalid(() -> resolve(engine, engine.initialState(), 1, MEMORIES));
        when(agent.reviseOutline(anyString(), anyString())).thenReturn(outline(List.of(new SceneEngine.Plan(
                "detour", "新安排", "当前环境", List.of("unknown"), "落实调整", "调整已落实"))));
        assertInvalid(() -> resolve(engine, engine.initialState(), 1, MEMORIES));
        when(agent.reviseOutline(anyString(), anyString())).thenReturn(outline(List.of(plan("detour", "改为撤下曲目"))));
        var result = resolve(engine, engine.initialState(), 1, MEMORIES);
        assertTrue(result.milestoneEnded());
        assertEquals("detour", result.scene().id());
        assertEquals("改为撤下曲目", result.scene().title());
        assertEquals(0, result.scene().turns());
        assertFalse(result.scene().ended());
    }

    @Test
    void milestoneCanContinueAcrossManyChoicesWithoutForcedClosure() {
        var engine = engine();
        when(agent.advance(anyString(), anyString())).thenReturn(beat(false));
        var state = engine.initialState();
        for (int i = 1; i <= 6; i++) {
            state = resolve(engine, state, i, MEMORIES).scene();
            assertFalse(state.ended());
        }
        assertEquals("practice", state.id());
        assertEquals(6, state.turns());
    }

    @Test
    void actualTerminalResultCanEndBeforeThePlannedLastMilestone() {
        var engine = engine();
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "演出取消，众人离开。", "教室已关闭。", BOTH, MEMORIES, true,
                "最终安排已经落实。", "失去演出机会，约定仍待处理。", true, "核心行动与最终选择均已落定。"));
        when(agent.reviseOutline(anyString(), anyString())).thenReturn(outline(List.of()));
        var result = resolve(engine, engine.initialState(), 1, MEMORIES);
        assertTrue(result.scene().ended());
        assertEquals(1, result.scene().turns());
    }

    @Test
    void participantOrderAloneDoesNotRequireAnEntranceOrExitNarration() {
        var engine = engine();
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "", "当前环境", List.of("xu-zhi", "lin-che"), MEMORIES, false, "", "", false, ""));
        var result = resolve(engine, engine.initialState(), 1, MEMORIES);
        assertEquals(2, result.scene().presentCharacterIds().size());
        assertFalse(result.milestoneEnded());
    }

    @Test
    void invalidParticipantsOrMissingWitnessMemoriesAreRejected() {
        var engine = engine();
        var badBeats = List.of(
                new CharacterAgent.StoryBeat("", "环境", List.of("unknown"), MEMORIES, false, "", "", false, ""),
                new CharacterAgent.StoryBeat("", "环境", List.of("lin-che", "lin-che"), MEMORIES, false, "", "", false, ""),
                new CharacterAgent.StoryBeat("", "环境", BOTH, Map.of("lin-che", "遗漏了沉默的人"), false, "", "", false, ""),
                new CharacterAgent.StoryBeat("", "环境", BOTH, Map.of("lin-che", "记忆", "xu-zhi", "记忆", "unknown", "多余"), false, "", "", false, ""),
                new CharacterAgent.StoryBeat("", "环境", List.of("lin-che"), MEMORIES, false, "", "", false, ""));
        for (var beat : badBeats) {
            when(agent.advance(anyString(), anyString())).thenReturn(beat);
            assertInvalid(() -> resolve(engine, engine.initialState(), 1, MEMORIES));
        }
    }

    @Test
    void enteringCharacterCannotBeGivenMemoriesOfEarlierConversation() {
        var engine = engine();
        var alone = new SceneEngine.State("practice", "排练", "只有玩家和林澈。", List.of("lin-che"), 1, false);
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走了进来。", "三人在教室会合。", BOTH, MEMORIES, false, "", "", false, ""));
        assertInvalid(() -> resolve(engine, alone, 2, Map.of("lin-che", "私下记忆")));
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走了进来。", "三人在教室会合。", BOTH, Map.of("lin-che", "私下记忆"), false, "", "", false, ""));
        var result = resolve(engine, alone, 2, Map.of("lin-che", "私下记忆"));
        assertEquals(BOTH, result.scene().presentCharacterIds());
        assertFalse(result.memories().containsKey("xu-zhi"));
    }

    @Test
    void initialOutlineRequiresDevelopmentEndingsUniqueMilestonesAndKnownCast() {
        assertThrows(IllegalStateException.class, () -> new SceneEngine(
                outline(List.of(plan("same", "排练"), plan("same", "候场"))), NAMES, agent));
        assertThrows(IllegalStateException.class, () -> new SceneEngine(
                new SceneEngine.Outline("", "结局方向", List.of(plan("practice", "排练"))), NAMES, agent));
        assertThrows(IllegalStateException.class, () -> new SceneEngine(outline(List.of(new SceneEngine.Plan(
                "practice", "排练", "教室", List.of("unknown"), "目标", "结束条件"))), NAMES, agent));
    }

    private SceneEngine engine() {
        return new SceneEngine(outline(List.of(plan("practice", "排练"), plan("stage", "上台前"))), NAMES, agent);
    }

    private SceneEngine.Outline outline(List<SceneEngine.Plan> plans) {
        return new SceneEngine.Outline("沿实际行动继续演出安排。", "按实际结果交代演出与关系。", plans);
    }

    private SceneEngine.Plan plan(String id, String title) {
        return new SceneEngine.Plan(id, title, "当前环境", BOTH, "完成眼前的行动", "行动已产生实际结果");
    }

    private CharacterAgent.StoryBeat beat(boolean ended) {
        return new CharacterAgent.StoryBeat(ended ? "本幕核心行动已落实。" : "", "当前环境", BOTH, MEMORIES,
                ended, ended ? "本幕目标已产生结果。" : "", ended ? "结果影响后续安排。" : "", false, "");
    }

    private SceneEngine.Resolution resolve(SceneEngine engine, SceneEngine.State state, int number, Map<String, String> memories) {
        return engine.resolve("故事", state, engine.initialOutline(), List.of(), number, "实际事件记录", memories);
    }

    private void assertInvalid(Runnable action) {
        assertEquals(502, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
