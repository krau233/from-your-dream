package com.fromyourdream.backend.service;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fromyourdream.backend.controller.GameController;
import com.fromyourdream.backend.service.GameService.GameView;
import com.fromyourdream.backend.service.GameService.TurnRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameServiceTest {

    private static final List<String> BOTH = List.of("lin-che", "xu-zhi");
    private static final Map<String, String> MEMORIES = Map.of(
            "lin-che", "听见玩家和许栀讨论排练。", "xu-zhi", "听见玩家和林澈讨论排练。");
    private final CharacterAgent.Reply lin = new CharacterAgent.Reply("", "最后两小节，再试一次。", "calm", "玩家愿意一起排练。");
    private final CharacterAgent.Reply xu = new CharacterAgent.Reply("", "行，我再唱一遍。", "happy", "玩家愿意一起排练。");
    private RedisService accounts;
    private CharacterAgent agent;
    private GameService games;

    @BeforeEach
    void setup() throws Exception {
        accounts = mock(RedisService.class);
        when(accounts.userExists(anyString())).thenReturn(true);
        agent = mock(CharacterAgent.class);
        when(agent.planResponses(anyList(), anyString(), anyString())).thenAnswer(call -> {
            List<String> present = call.getArgument(0);
            var selected = new ArrayList<>(present);
            if (selected.remove("xu-zhi")) selected.add(0, "xu-zhi");
            return new CharacterAgent.ResponsePlan(List.copyOf(selected));
        });
        when(agent.respond(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0, String.class).contains("你叫林澈") ? lin : xu);
        when(agent.advance(anyString(), anyString())).thenReturn(stay("rehearsal"));
        games = new GameService(accounts, agent);
    }

    @Test
    void openingSceneIsSharedAndDoesNotConsumeATurn() {
        var initial = games.get("Account1");
        assertEquals(0, initial.turn());
        assertTrue(initial.history().isEmpty());
        assertEquals(BOTH, initial.scene().presentCharacterIds());
        assertFalse(initial.openingPrompt().dialogue().isBlank());
        assertFalse(initial.openingPrompt().suggestedReplies().isEmpty());
        assertEquals(initial, games.get("Account1"));
        verifyNoInteractions(agent);

        String input = initial.openingPrompt().suggestedReplies().get(0);
        var next = games.turn("Account1", request(initial, "request-01", input));
        assertEquals(1, next.turn());
        assertEquals(2, next.history().get(0).responses().size());
        for (String name : List.of("林澈", "许栀")) {
            assertTrue(contextFor(name, input).contains(initial.opening()));
            assertTrue(contextFor(name, input).contains(initial.openingPrompt().dialogue()));
        }
    }

    @Test
    void everyonePresentHearsPublicSpeechAndTheEarlierReplyInThisRound() {
        when(agent.planResponses(anyList(), anyString(), eq("林澈，刚才那一拍太快了")))
                .thenReturn(new CharacterAgent.ResponsePlan(BOTH));
        var first = games.get("Account1");
        first = games.turn("Account1", request(first, "request-01", "我想先听你们合一遍"));
        assertEquals("xu-zhi", first.history().get(0).responses().get(0).characterId());
        assertTrue(contextFor("林澈", "我想先听你们合一遍").contains(xu.dialogue()));
        first = games.turn("Account1", request(first, "request-02", "林澈，刚才那一拍太快了"));
        assertEquals("lin-che", first.history().get(1).responses().get(0).characterId());
        assertTrue(contextFor("许栀", "林澈，刚才那一拍太快了").contains(lin.dialogue()));
        for (String name : List.of("林澈", "许栀")) {
            String context = contextFor(name, "林澈，刚才那一拍太快了");
            assertTrue(context.contains("我想先听你们合一遍"));
            assertTrue(context.contains(lin.dialogue()));
            assertTrue(context.contains(xu.dialogue()));
        }
        assertTrue(games.get("Account2").history().isEmpty());
    }

    @Test
    void silentCharacterStillWitnessesTheRoundAndReceivesItsMemory() {
        when(agent.respond(contains("你叫许栀"), anyString(), anyString()))
                .thenReturn(new CharacterAgent.Reply("", "", "calm", "听见玩家提出先放轻伴奏。"));
        var first = games.get("Account1");
        first = games.turn("Account1", request(first, "request-01", "先把伴奏放轻一点"));
        assertEquals(BOTH, first.history().get(0).witnessIds());
        assertEquals(List.of("lin-che"), first.history().get(0).responses().stream()
                .map(GameService.CharacterResponse::characterId).toList());
        games.turn("Account1", request(first, "request-02", "刚才怎么样"));
        assertTrue(contextFor("许栀", "刚才怎么样").contains("先把伴奏放轻一点"));
        assertTrue(contextFor("许栀", "刚才怎么样").contains(MEMORIES.get("xu-zhi")));
    }

    @Test
    void everyoneMayRemainSilentAndSceneStillGetsResolved() {
        when(agent.respond(anyString(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.Reply("", "", "calm", "听见玩家的话，暂时没有开口。"));
        var initial = games.get("Account1");
        var next = games.turn("Account1", request(initial, "request-01", "让我静一会儿"));
        assertTrue(next.history().get(0).responses().isEmpty());
        assertEquals(BOTH, next.history().get(0).witnessIds());
        assertEquals(1, next.scene().turns());
        verify(agent).advance(anyString(), anyString());
    }

    @Test
    void sceneCanScheduleNoSpeakersWhileAllWitnessesKeepTheExperience() {
        when(agent.planResponses(anyList(), anyString(), anyString()))
                .thenReturn(new CharacterAgent.ResponsePlan(List.of()))
                .thenReturn(new CharacterAgent.ResponsePlan(BOTH));
        var initial = games.get("Account1");
        var next = games.turn("Account1", request(initial, "request-01", "先安静听一会儿"));
        assertEquals(BOTH, next.history().get(0).witnessIds());
        assertTrue(next.history().get(0).responses().isEmpty());
        verify(agent, never()).respond(anyString(), anyString(), anyString());
        games.turn("Account1", request(next, "request-02", "现在感觉怎么样"));
        for (String name : List.of("林澈", "许栀")) {
            assertTrue(contextFor(name, "现在感觉怎么样").contains("先安静听一会儿"));
        }
        assertTrue(contextFor("许栀", "现在感觉怎么样").contains(MEMORIES.get("xu-zhi")));
    }

    @Test
    void playerAloneCanContinueTheSceneWithoutCallingAbsentCharacters() {
        when(agent.advance(anyString(), anyString())).thenReturn(
                beat("rehearsal", false, List.of(), MEMORIES, "两人去走廊拿东西，玩家暂时独自留在教室。"),
                beat("rehearsal", false, List.of(), Map.of(), "走廊传来脚步声。"));
        var current = games.get("Account1");
        current = games.turn("Account1", request(current, "request-01", "我留在这里等你们"));
        assertTrue(current.scene().presentCharacterIds().isEmpty());
        current = games.turn("Account1", request(current, "request-02", "看看谱子上的结尾"));
        assertTrue(current.history().get(1).witnessIds().isEmpty());
        assertTrue(current.history().get(1).responses().isEmpty());
        verify(agent, times(2)).respond(anyString(), anyString(), anyString());
        verify(agent, times(2)).advance(anyString(), anyString());
    }

    @Test
    void absentCharacterIsNotCalledAndDoesNotLearnMissedConversationOnReturn() {
        var onlyLin = List.of("lin-che");
        String secret = "紫色信封是我们的秘密";
        String privateMemory = "玩家告诉我紫色信封是我们的秘密。";
        when(agent.advance(anyString(), anyString())).thenReturn(
                beat("rehearsal", false, onlyLin, MEMORIES, "许栀去走廊取水，林澈留在教室。"),
                beat("rehearsal", false, onlyLin, Map.of("lin-che", privateMemory), ""),
                beat("backstage", true, BOTH, Map.of("lin-che", privateMemory), "准备时间到了，许栀在侧幕与大家会合。"),
                beat("backstage", false, BOTH, Map.of("lin-che", privateMemory, "xu-zhi", MEMORIES.get("xu-zhi")), ""));
        var current = games.get("Account1");
        current = games.turn("Account1", request(current, "request-01", "你先去取水吧"));
        assertEquals(onlyLin, current.scene().presentCharacterIds());
        current = games.turn("Account1", request(current, "request-02", secret));
        assertEquals(onlyLin, current.history().get(1).witnessIds());
        verify(agent, never()).respond(contains("你叫许栀"), anyString(), eq(secret));
        current = games.turn("Account1", request(current, "request-03", "等她回来就去候场"));
        assertEquals("backstage", current.scene().id());
        current = games.turn("Account1", request(current, "request-04", "现在准备好了吗"));
        assertTrue(contextFor("林澈", "现在准备好了吗").contains(secret));
        assertFalse(contextFor("许栀", "现在准备好了吗").contains(secret));
        assertFalse(contextFor("许栀", "现在准备好了吗").contains(privateMemory));
        assertTrue(contextFor("许栀", "现在准备好了吗").contains("你先去取水吧"));
    }

    @Test
    void retriesDoNotRepeatCharacterCallsOrSceneResolutionAndRejectChangedInput() {
        var initial = games.get("Account1");
        var request = request(initial, "request-01", "一起排练吧");
        var next = games.turn("Account1", request);
        assertEquals(next, games.turn("Account1", request));
        assertStatus(409, () -> games.turn("Account1", request(initial, "request-01", "别的内容")));
        assertStatus(409, () -> games.turn("Account1", request(initial, "request-02", "旧页面的输入")));
        assertStatus(409, () -> games.turn("Account1", new TurnRequest(
                initial.gameId(), "request-01", 0, "一起排练吧", true)));
        verify(agent, times(2)).respond(anyString(), anyString(), anyString());
        verify(agent).advance(anyString(), anyString());
    }

    @Test
    void laterCharacterFailureRollsBackEarlierResponseAndMemory() {
        when(agent.respond(contains("你叫林澈"), anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "生成失败"))
                .thenReturn(new CharacterAgent.Reply("", "", "bad-emotion", "不可保存的记忆"));
        var initial = games.get("Account1");
        var request = request(initial, "request-01", "一起排练吧");
        assertStatus(502, () -> games.turn("Account1", request));
        assertEquals(initial, games.get("Account1"));
        assertStatus(502, () -> games.turn("Account1", request));
        assertEquals(initial, games.get("Account1"));
        verify(agent, never()).advance(anyString(), anyString());
    }

    @Test
    void sceneResolutionFailureRollsBackAllResponsesAndCanBeRetried() {
        when(agent.advance(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "叙事者暂不可用"))
                .thenReturn(beat("rehearsal", false, BOTH, Map.of("lin-che", "漏掉另一位在场者"), ""))
                .thenReturn(stay("rehearsal"));
        var initial = games.get("Account1");
        var request = request(initial, "request-01", "排练吧");
        assertStatus(502, () -> games.turn("Account1", request));
        assertEquals(initial, games.get("Account1"));
        assertStatus(502, () -> games.turn("Account1", request));
        assertEquals(initial, games.get("Account1"));
        assertEquals(1, games.turn("Account1", request).turn());
    }

    @Test
    void manualPauseDoesNotAutomaticallyEndTheWholeScene() {
        var initial = games.get("Account1");
        var next = games.turn("Account1", new TurnRequest(initial.gameId(), "request-end", 0, "", true));
        assertEquals("rehearsal", next.scene().id());
        assertFalse(next.history().get(0).sceneEnded());
        verify(agent).advance(anyString(), anyString());
    }

    @Test
    void outlineTransitionsAndTerminalSceneCanFinishBeforeGlobalLimit() {
        when(agent.advance(anyString(), anyString())).thenReturn(
                move("backstage"), move("performance"), finish());
        when(agent.conclude(anyString(), anyString())).thenReturn("演出结束，你们把谱子留了下来。");
        var current = games.get("Account1");
        current = games.turn("Account1", request(current, "request-01", "按这个结尾唱吧"));
        assertEquals("backstage", current.scene().id());
        assertEquals(0, current.scene().turns());
        assertTrue(current.history().get(0).sceneEnded());
        assertEquals("rehearsal", current.history().get(0).sceneId());
        assertEquals("backstage", current.history().get(0).nextSceneId());
        current = games.turn("Account1", request(current, "request-02", "我会陪你们上台"));
        current = games.turn("Account1", request(current, "request-03", "一起把歌唱完"));
        assertTrue(current.ended());
        assertTrue(current.scene().ended());
        assertEquals(3, current.turn());
        assertStatus(409, () -> games.turn("Account1", request(games.get("Account1"), "request-after", "再聊一段")));
    }

    @Test
    void failedEndingRollsBackFinalSceneAndRetryClosesGame() {
        when(agent.advance(anyString(), anyString())).thenReturn(
                stay("rehearsal"), stay("rehearsal"), move("backstage"),
                stay("backstage"), stay("backstage"), move("performance"), stay("performance"), finish());
        when(agent.conclude(anyString(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "结局失败"))
                .thenReturn("演出没有如期开始，但那个约定保留了下来。");
        var current = games.get("Account1");
        for (int i = 1; i < 8; i++) current = games.turn("Account1", request(current, "request-0" + i, "第" + i + "次互动"));
        var last = request(current, "request-final", "谢谢你们陪我到这里");
        assertStatus(502, () -> games.turn("Account1", last));
        assertEquals(current, games.get("Account1"));
        var completed = games.turn("Account1", last);
        assertTrue(completed.ended());
        assertEquals(8, completed.turn());
        assertEquals(completed, games.turn("Account1", last));
        var history = ArgumentCaptor.forClass(String.class);
        verify(agent, times(2)).conclude(anyString(), history.capture());
        assertTrue(history.getValue().contains(current.openingPrompt().dialogue()));
        assertTrue(history.getValue().contains("在场：林澈、许栀"));
        assertTrue(history.getValue().contains(lin.dialogue()));
        assertTrue(history.getValue().contains(xu.dialogue()));
    }

    @Test
    void resetRestoresOpeningAndInvalidatesOldRequestsForOnlyThisAccount() {
        var first = games.get("Account1");
        games.turn("Account1", request(first, "request-01", "你好"));
        var second = games.get("Account2");
        games.turn("Account2", request(second, "request-02", "你好"));
        var reset = games.reset("Account1", first.gameId());
        assertEquals(0, reset.turn());
        assertEquals(first.openingPrompt(), reset.openingPrompt());
        assertEquals(first.scene(), reset.scene());
        assertNotEquals(first.gameId(), reset.gameId());
        assertStatus(409, () -> games.turn("Account1", request(first, "request-old", "旧请求")));
        assertEquals(1, games.get("Account2").turn());
    }

    @Test
    void inFlightTurnAllowsReadingPreviousSnapshotButRejectsAnotherWriter() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(agent.respond(anyString(), anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return xu;
        });
        var initial = games.get("Account1");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> games.turn("Account1", request(initial, "request-01", "你好")));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(initial, games.get("Account1"));
            assertStatus(409, () -> games.turn("Account1", request(initial, "request-02", "再说一句")));
            assertStatus(409, () -> games.reset("Account1", initial.gameId()));
            finish.countDown();
            assertEquals(2, future.get(3, TimeUnit.SECONDS).history().get(0).responses().size());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void springAiConvertsSilentReplyAndSceneResolutionWithoutExternalModel() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"narration":"","dialogue":"","emotion":"calm","memory":"听见玩家愿意一起排练，暂未开口。"}
                """))));
        var local = new CharacterAgent(ChatClient.create(model));
        assertEquals("", local.respond("林澈", "许栀与玩家在场", "你好").dialogue());
        ChatModel narrator = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"narration":"排练结束，大家走到侧幕。","environment":"候场中。","presentCharacterIds":["lin-che","xu-zhi"],"memories":{"lin-che":"已练完结尾。","xu-zhi":"已练完结尾。"},"sceneEnded":true,"targetSceneId":"backstage"}
                """))));
        var beat = new CharacterAgent(ChatClient.create(narrator)).advance("最后一场演出", "排练已完成");
        assertTrue(beat.sceneEnded());
        assertEquals("backstage", beat.targetSceneId());
        assertEquals(BOTH, beat.presentCharacterIds());
        assertEquals("已练完结尾。", beat.memories().get("xu-zhi"));
        ChatModel planner = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("{\"speakerIds\":[]}"))));
        assertTrue(new CharacterAgent(ChatClient.create(planner)).planResponses(BOTH, "大家在听余音", "安静听一会儿")
                .speakerIds().isEmpty());
    }

    @Test
    void deepSeekAdapterReadsActualHttpResponse() throws Exception {
        var mapper = JsonMapper.builder().build();
        byte[] response = mapper.writeValueAsString(Map.of(
                "id", "local-check", "object", "chat.completion", "created", 1792254000,
                "model", "deepseek-chat", "choices", List.of(Map.of("index", 0,
                        "message", Map.of("role", "assistant", "content", mapper.writeValueAsString(lin)), "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)))
                .getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var environment = new MockEnvironment().withProperty("dream.ai.api-key", "local-test-only")
                    .withProperty("dream.ai.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals(lin, new CharacterAgent(environment).respond("林澈", "尚无经历", "你好"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void controllerAcceptsSceneSpeechWithoutARecipientAndReturnsMultipleResponses() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new GameController(games)).build();
        var mapper = JsonMapper.builder().build();
        var initial = games.get("Account1");
        mvc.perform(get("/api/game")).andExpect(status().isBadRequest());
        when(accounts.userExists("Missing1")).thenReturn(false);
        mvc.perform(get("/api/game").header("X-User-Id", "Missing1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/game").header("X-User-Id", "user_Account1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scene.id").value("rehearsal"))
                .andExpect(jsonPath("$.data.scene.presentCharacterIds.length()").value(2));
        mvc.perform(post("/api/game/turns").header("X-User-Id", "Account1")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "gameId", initial.gameId(), "requestId", "request-01", "expectedTurn", 0, "message", "你们好"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.history[0].responses.length()").value(2))
                .andExpect(jsonPath("$.data.history[0].witnessIds.length()").value(2));
    }

    private TurnRequest request(GameView game, String requestId, String input) {
        return new TurnRequest(game.gameId(), requestId, game.turn(), input, false);
    }

    private CharacterAgent.StoryBeat stay(String id) {
        return beat(id, false, BOTH, MEMORIES, "");
    }

    private CharacterAgent.StoryBeat move(String id) {
        return beat(id, true, BOTH, MEMORIES, "这一幕告一段落，大家继续眼前的事。");
    }

    private CharacterAgent.StoryBeat finish() {
        return beat("performance", true, BOTH, MEMORIES, "最后一个音落下，演出已经结束。");
    }

    private CharacterAgent.StoryBeat beat(String id, boolean ended, List<String> present,
                                          Map<String, String> memories, String narration) {
        return new CharacterAgent.StoryBeat(narration, "当前准备进度与在场者保持可观察的一致。", present, memories, ended, id);
    }

    private String contextFor(String name, String input) {
        return mockingDetails(agent).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("respond")
                        && call.getArgument(0, String.class).contains("你叫" + name)
                        && call.getArgument(2, String.class).equals(input))
                .map(call -> call.getArgument(1, String.class)).reduce((first, last) -> last).orElseThrow();
    }

    private void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
