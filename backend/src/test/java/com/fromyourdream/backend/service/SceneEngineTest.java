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
    void environmentAndPresenceAreUpdatedWithoutEndingTheScene() {
        var engine = engine(3);
        var initial = engine.initialState();
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走到门外接水。", "林澈留在琴边，教室里安静下来。", List.of("lin-che"), MEMORIES, false, "practice"));
        var result = engine.resolve("故事", initial, 1, "本轮公开事件", MEMORIES, false);
        assertEquals("practice", result.scene().id());
        assertEquals(List.of("lin-che"), result.scene().presentCharacterIds());
        assertEquals("林澈留在琴边，教室里安静下来。", result.scene().environment());
        assertEquals(1, result.scene().turns());
        assertFalse(result.sceneEnded());
        assertFalse(result.scene().ended());
        assertEquals(BOTH, initial.presentCharacterIds());
    }

    @Test
    void responderPlanCanBeEmptyButCannotNameAbsentOrDuplicateCharacters() {
        var engine = engine(3);
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of()));
        assertTrue(engine.responders(engine.initialState(), "听琴", "安静一会儿").isEmpty());
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("unknown")));
        assertInvalid(() -> engine.responders(engine.initialState(), "公开场景", "你好"));
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("lin-che", "lin-che")));
        assertInvalid(() -> engine.responders(engine.initialState(), "公开场景", "你好"));
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of("xu-zhi")));
        var aloneWithLin = new SceneEngine.State("practice", "排练", "许栀不在。", List.of("lin-che"), 0, false);
        assertInvalid(() -> engine.responders(aloneWithLin, "仅林澈在场", "你好"));
    }

    @Test
    void engineOnlyAllowsTheNextAuthoredSceneAndKeepsItsAuthoredTitle() {
        var engine = engine(3);
        when(agent.advance(anyString(), anyString())).thenReturn(beat(true, "invented"));
        assertInvalid(() -> engine.resolve("故事", engine.initialState(), 1, "事件", MEMORIES, false));
        when(agent.advance(anyString(), anyString())).thenReturn(beat(false, "stage"));
        assertInvalid(() -> engine.resolve("故事", engine.initialState(), 1, "事件", MEMORIES, false));
        when(agent.advance(anyString(), anyString())).thenReturn(beat(true, "stage"));
        var result = engine.resolve("故事", engine.initialState(), 1, "事件", MEMORIES, false);
        assertTrue(result.sceneEnded());
        assertEquals("stage", result.scene().id());
        assertEquals("上台前", result.scene().title());
        assertEquals(0, result.scene().turns());
        assertFalse(result.scene().ended());
    }

    @Test
    void sceneBudgetComesFromOutlineAndCanExceedThreeRounds() {
        var engine = new SceneEngine(List.of(plan("practice", "排练", 5)), NAMES, 5, agent);
        when(agent.advance(anyString(), anyString())).thenReturn(beat(false, "practice"));
        var state = engine.initialState();
        for (int i = 1; i <= 4; i++) {
            state = engine.resolve("故事", state, i, "仍在交流", MEMORIES, false).scene();
            assertFalse(state.ended());
        }
        var atLimit = state;
        assertInvalid(() -> engine.resolve("故事", atLimit, 5, "时间到了", MEMORIES, false));
        when(agent.advance(anyString(), anyString())).thenReturn(beat(true, "practice"));
        assertTrue(engine.resolve("故事", atLimit, 5, "目标未完成也须收束", MEMORIES, false).scene().ended());
    }

    @Test
    void terminalSceneCanEndAsSoonAsItsConditionIsMet() {
        var engine = new SceneEngine(List.of(plan("practice", "排练", 3)), NAMES, 3, agent);
        when(agent.advance(anyString(), anyString())).thenReturn(beat(true, "practice"));
        var result = engine.resolve("故事", engine.initialState(), 1, "实际行动已经结束", MEMORIES, false);
        assertTrue(result.scene().ended());
        assertEquals(1, result.scene().turns());
    }

    @Test
    void participantOrderAloneDoesNotRequireAnEntranceOrExitNarration() {
        var engine = engine(3);
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "", "当前环境", List.of("xu-zhi", "lin-che"), MEMORIES, false, "practice"));
        var result = engine.resolve("故事", engine.initialState(), 1, "普通交流", MEMORIES, false);
        assertEquals(2, result.scene().presentCharacterIds().size());
        assertFalse(result.sceneEnded());
    }

    @Test
    void invalidParticipantsOrMissingWitnessMemoriesAreRejected() {
        var engine = engine(3);
        var badBeats = List.of(
                new CharacterAgent.StoryBeat("", "环境", List.of("unknown"), MEMORIES, false, "practice"),
                new CharacterAgent.StoryBeat("", "环境", List.of("lin-che", "lin-che"), MEMORIES, false, "practice"),
                new CharacterAgent.StoryBeat("", "环境", BOTH, Map.of("lin-che", "遗漏了沉默的人"), false, "practice"),
                new CharacterAgent.StoryBeat("", "环境", BOTH, Map.of("lin-che", "记忆", "xu-zhi", "记忆", "unknown", "多余"), false, "practice"),
                new CharacterAgent.StoryBeat("", "环境", List.of("lin-che"), MEMORIES, false, "practice"));
        for (var beat : badBeats) {
            when(agent.advance(anyString(), anyString())).thenReturn(beat);
            assertInvalid(() -> engine.resolve("故事", engine.initialState(), 1, "事件", MEMORIES, false));
        }
    }

    @Test
    void enteringCharacterCannotBeGivenMemoriesOfEarlierConversation() {
        var engine = engine(3);
        var alone = new SceneEngine.State("practice", "排练", "只有玩家和林澈。", List.of("lin-che"), 1, false);
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走了进来。", "三人在教室会合。", BOTH, MEMORIES, false, "practice"));
        assertInvalid(() -> engine.resolve("故事", alone, 2, "此前许栀不在场", Map.of("lin-che", "私下记忆"), false));
        when(agent.advance(anyString(), anyString())).thenReturn(new CharacterAgent.StoryBeat(
                "许栀走了进来。", "三人在教室会合。", BOTH, Map.of("lin-che", "私下记忆"), false, "practice"));
        var result = engine.resolve("故事", alone, 2, "此前许栀不在场", Map.of("lin-che", "私下记忆"), false);
        assertEquals(BOTH, result.scene().presentCharacterIds());
        assertFalse(result.memories().containsKey("xu-zhi"));
    }

    @Test
    void outlineRejectsUnknownCastDuplicateScenesAndImpossibleBudget() {
        assertThrows(IllegalStateException.class, () -> new SceneEngine(
                List.of(plan("same", "排练", 2), plan("same", "候场", 2)), NAMES, 4, agent));
        assertThrows(IllegalStateException.class, () -> new SceneEngine(
                List.of(plan("practice", "排练", 3), plan("stage", "上台前", 3)), NAMES, 5, agent));
        assertThrows(IllegalStateException.class, () -> new SceneEngine(List.of(new SceneEngine.Plan(
                "practice", "排练", "教室", List.of("unknown"), "目标", "结束条件", 1)), NAMES, 3, agent));
    }

    private SceneEngine engine(int sceneBudget) {
        return new SceneEngine(List.of(plan("practice", "排练", sceneBudget), plan("stage", "上台前", 2)),
                NAMES, sceneBudget + 2, agent);
    }

    private SceneEngine.Plan plan(String id, String title, int turns) {
        return new SceneEngine.Plan(id, title, "当前环境", BOTH, "完成眼前的行动", "行动完成或时间用尽", turns);
    }

    private CharacterAgent.StoryBeat beat(boolean ended, String target) {
        return new CharacterAgent.StoryBeat(ended ? "本幕结束。" : "", "当前环境", BOTH, MEMORIES, ended, target);
    }

    private void assertInvalid(Runnable action) {
        assertEquals(502, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
