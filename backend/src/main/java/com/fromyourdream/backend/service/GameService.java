package com.fromyourdream.backend.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class GameService {

    private static final Set<String> EMOTIONS = Set.of("calm", "happy", "sad", "surprised", "angry");
    private final RedisService accounts;
    private final CharacterAgent agent;
    private final Story story;
    private final SceneEngine scenes;
    // ponytail: 单实例内存原型；重启清空进度，需要持久化时替换这里的存储。
    private final Map<String, Slot> games = new ConcurrentHashMap<>();

    public GameService(RedisService accounts, CharacterAgent agent) throws IOException {
        this.accounts = accounts;
        this.agent = agent;
        try (var input = new ClassPathResource("demo-story.json").getInputStream()) {
            story = JsonMapper.builder().build().readValue(input, Story.class);
        }
        if (story.maxTurns() < 1 || story.maxTurns() > 20 || story.characters() == null || story.characters().isEmpty()) {
            throw new IllegalStateException("示例故事需要 1 至 20 段互动及至少一个角色");
        }
        scenes = new SceneEngine(story.scenes(), story.characters().stream().collect(
                Collectors.toMap(CharacterProfile::id, CharacterProfile::name)), story.maxTurns(), agent);
        OpeningPrompt opening = story.openingPrompt();
        if (!text(story.opening(), 2000) || opening == null
                || !scenes.initialState().presentCharacterIds().contains(opening.characterId())
                || !text(opening.dialogue(), 1000) || opening.suggestedReplies() == null
                || opening.suggestedReplies().size() > 3
                || opening.suggestedReplies().stream().anyMatch(reply -> !text(reply, 200))) {
            throw new IllegalStateException("示例故事需要开场描写、在场角色的开场台词及最多三条建议回复");
        }
    }

    public GameView get(String userId) {
        String code = accountCode(userId);
        return view(code, slot(code).snapshot);
    }

    public List<Turn> history(String userId) {
        return get(userId).history();
    }

    public GameView turn(String userId, TurnRequest request) {
        String code = accountCode(userId);
        if (request == null || request.requestId() == null
                || !request.requestId().matches("[a-zA-Z0-9-]{8,64}")
                || request.message() == null || (request.message().isBlank() && !request.endConversation())
                || request.message().length() > 1000) {
            throw error(HttpStatus.BAD_REQUEST, "请提供请求标识及 1 至 1000 字的输入。");
        }
        String input = request.message().isBlank() ? "（暂时结束交谈，继续眼前的事）" : request.message().trim();
        Slot slot = slot(code);
        acquire(slot);
        try {
            Snapshot current = slot.snapshot;
            requireGame(current, request.gameId());
            for (Turn previous : current.turns()) {
                if (previous.requestId().equals(request.requestId())) {
                    if (!previous.playerInput().equals(input) || previous.endConversation() != request.endConversation()) {
                        throw error(HttpStatus.CONFLICT, "相同请求标识不能用于不同内容。");
                    }
                    return view(code, current);
                }
            }
            if (request.expectedTurn() != current.turns().size()) {
                throw error(HttpStatus.CONFLICT, "进度已更新，请刷新后重试。");
            }
            if (current.scene().ended() || !current.ending().isEmpty()) {
                throw error(HttpStatus.CONFLICT, "这一局已结束，可以查看历史或重新开始。");
            }
            int number = current.turns().size() + 1;
            List<String> witnesses = List.copyOf(current.scene().presentCharacterIds());
            var responses = new ArrayList<CharacterResponse>();
            var memories = new HashMap<>(current.memories());
            var perceptions = new HashMap<String, String>();
            for (String id : witnesses) perceptions.put(id, context(current, character(id), number));
            String candidateContext = witnesses.stream().map(id -> character(id).personality()
                    + "\n该角色可知的经历：\n" + perceptions.get(id)).collect(Collectors.joining("\n\n"));
            for (String id : scenes.responders(current.scene(), candidateContext, input)) {
                CharacterProfile character = character(id);
                String context = perceptions.get(id)
                        + "\n本轮玩家发言后，在你之前已经发生的公开回应：\n"
                        + (responses.isEmpty() ? "暂时没有其他人的可观察回应。" : responseTranscript(responses));
                if (request.endConversation()) context += "\n玩家想暂时结束交谈并继续眼前的事，你可以简短收束或保持沉默。";
                CharacterAgent.Reply reply = agent.respond(character.personality(), context, input);
                validate(reply);
                memories.put(character.id(), reply.memory().trim());
                if (!reply.narration().isBlank() || !reply.dialogue().isBlank()) {
                    responses.add(new CharacterResponse(character.id(), character.name(), reply.narration().trim(),
                            reply.dialogue().trim(), reply.emotion()));
                }
            }
            var turns = new ArrayList<>(current.turns());
            var turn = new Turn(number, request.requestId(), input, current.scene().id(), witnesses,
                    List.copyOf(responses), "", false, current.scene().id(), request.endConversation(), Instant.now());
            turns.add(turn);
            var sceneTurns = turns.stream().filter(item -> item.sceneId().equals(current.scene().id())).toList();
            String sceneHistory = (current.scene().id().equals(scenes.initialState().id())
                    ? openingTranscript() + "\n\n" : "") + transcript(sceneTurns);
            var resolution = scenes.resolve(story.premise(), current.scene(), number, sceneHistory,
                    memories, request.endConversation());
            memories.putAll(resolution.memories());
            turns.set(turns.size() - 1, new Turn(number, request.requestId(), input, current.scene().id(), witnesses,
                    List.copyOf(responses), resolution.narration(), resolution.sceneEnded(), resolution.scene().id(),
                    request.endConversation(), turn.createdAt()));
            String ending = "";
            if (resolution.scene().ended()) {
                ending = agent.conclude(story.premise(), openingTranscript() + "\n\n" + transcript(turns));
                if (!text(ending, 6000)) throw error(HttpStatus.BAD_GATEWAY, "结局内容无效，本轮未保存。");
                ending = ending.trim();
            }
            // 所有在场角色、场景结算及结局都成功后，才一起提交新快照。
            slot.snapshot = new Snapshot(current.gameId(), List.copyOf(turns), Map.copyOf(memories),
                    resolution.scene(), ending);
            return view(code, slot.snapshot);
        } finally {
            slot.lock.unlock();
        }
    }

    public GameView reset(String userId, String gameId) {
        String code = accountCode(userId);
        Slot slot = slot(code);
        acquire(slot);
        try {
            requireGame(slot.snapshot, gameId);
            slot.snapshot = Snapshot.empty(scenes.initialState());
            return view(code, slot.snapshot);
        } finally {
            slot.lock.unlock();
        }
    }

    private String context(Snapshot snapshot, CharacterProfile character, int number) {
        List<Turn> known = snapshot.turns().stream().filter(turn -> turn.witnessIds().contains(character.id())).toList();
        int start = Math.max(0, known.size() - 4);
        return "故事：" + story.premise()
                + (scenes.initialState().presentCharacterIds().contains(character.id())
                    ? "\n你在开场时在场，亲历的公开事件：\n" + openingTranscript() : "")
                + "\n当前是第 " + number + "/" + story.maxTurns() + " 段互动。\n" + scenes.context(snapshot.scene())
                + "\n玩家是在场景中发言。所有在场者都能听见玩家和彼此说的话；点名某人不形成私聊。"
                + "\n你只知道当前可观察的环境、自己的记忆和你在场时亲历的记录，离场期间的交谈不知情。"
                + "\n你的已有记忆：" + snapshot.memories().getOrDefault(character.id(), "尚无额外记忆。")
                + "\n你在场时亲历的近期记录（包括他人发言与场景结算）：\n"
                + transcript(known.subList(start, known.size()));
    }

    private String openingTranscript() {
        OpeningPrompt opening = story.openingPrompt();
        return "[开场，在场：" + participantNames(scenes.initialState().presentCharacterIds()) + "]\n旁白："
                + story.opening() + "\n" + character(opening.characterId()).name() + "：" + opening.dialogue();
    }

    private String transcript(List<Turn> turns) {
        return turns.stream().map(turn -> "[第" + turn.number() + "段，场景：" + turn.sceneId()
                + "，在场：" + participantNames(turn.witnessIds()) + "]\n玩家：" + turn.playerInput()
                + "\n" + responseTranscript(turn.responses())
                + (turn.storyNarration().isEmpty() ? "" : "\n场景旁白：" + turn.storyNarration())
                + (turn.sceneEnded() ? "\n本幕已结束，目标场景：" + turn.nextSceneId() : ""))
                .collect(Collectors.joining("\n\n"));
    }

    private String responseTranscript(List<CharacterResponse> responses) {
        if (responses.isEmpty()) return "无人接话。";
        return responses.stream().map(reply ->
                (reply.narration().isEmpty() ? "" : reply.characterName() + "的动作：" + reply.narration() + "\n")
                + (reply.dialogue().isEmpty() ? "" : reply.characterName() + "：" + reply.dialogue()))
                .collect(Collectors.joining("\n"));
    }

    private String participantNames(List<String> ids) {
        return ids.isEmpty() ? "只有玩家" : ids.stream().map(id -> character(id).name()).collect(Collectors.joining("、"));
    }

    private CharacterProfile character(String id) {
        return story.characters().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private GameView view(String code, Snapshot snapshot) {
        return new GameView(code, snapshot.gameId(), story.title(), story.opening(), story.openingPrompt(),
                snapshot.turns().size(), story.maxTurns(), !snapshot.ending().isEmpty(), snapshot.ending(),
                story.characters().stream().map(item -> new CharacterView(item.id(), item.name(), item.intro())).toList(),
                snapshot.scene(), snapshot.turns());
    }

    private String accountCode(String userId) {
        String code = userId == null ? "" : userId.trim();
        if (code.startsWith("user_")) code = code.substring(5);
        if (!code.matches("[a-zA-Z0-9]{8}")) {
            throw error(HttpStatus.BAD_REQUEST, "账号 ID 应为生成的 8 位字符串或 user_ 前缀形式。");
        }
        try {
            if (!accounts.userExists(code)) throw error(HttpStatus.NOT_FOUND, "账号不存在，请先生成账号 ID。");
        } catch (DataAccessException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "账号服务暂不可用，请稍后重试。");
        }
        return code;
    }

    private Slot slot(String code) {
        return games.computeIfAbsent(code, ignored -> new Slot(scenes.initialState()));
    }

    private void acquire(Slot slot) {
        if (!slot.lock.tryLock()) throw error(HttpStatus.CONFLICT, "这一账号正在生成内容，请等待完成后再操作。");
    }

    private void requireGame(Snapshot snapshot, String gameId) {
        if (!snapshot.gameId().equals(gameId)) throw error(HttpStatus.CONFLICT, "进度已重新开始，请刷新后重试。");
    }

    private void validate(CharacterAgent.Reply reply) {
        if (reply == null || reply.narration() == null || reply.narration().length() > 1000
                || reply.dialogue() == null || reply.dialogue().length() > 2000 || !text(reply.memory(), 1600)
                || !EMOTIONS.contains(reply.emotion() == null ? "" : reply.emotion())) {
            throw error(HttpStatus.BAD_GATEWAY, "角色返回内容格式无效，本轮未保存，请重试。");
        }
    }

    private boolean text(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private static class Slot {
        final ReentrantLock lock = new ReentrantLock();
        volatile Snapshot snapshot;
        Slot(SceneEngine.State scene) { snapshot = Snapshot.empty(scene); }
    }

    private record Snapshot(String gameId, List<Turn> turns, Map<String, String> memories,
                            SceneEngine.State scene, String ending) {
        static Snapshot empty(SceneEngine.State scene) {
            return new Snapshot(UUID.randomUUID().toString(), List.of(), Map.of(), scene, "");
        }
    }

    public record TurnRequest(String gameId, String requestId, int expectedTurn, String message, Boolean endConversation) {
        public TurnRequest { endConversation = Boolean.TRUE.equals(endConversation); }
    }
    public record CharacterResponse(String characterId, String characterName, String narration, String dialogue,
                                    String emotion) {}
    public record Turn(int number, String requestId, String playerInput, String sceneId, List<String> witnessIds,
                       List<CharacterResponse> responses, String storyNarration, boolean sceneEnded,
                       String nextSceneId, boolean endConversation, Instant createdAt) {}
    public record OpeningPrompt(String characterId, String dialogue, List<String> suggestedReplies) {}
    public record CharacterView(String id, String name, String intro) {}
    public record GameView(String userId, String gameId, String title, String opening, OpeningPrompt openingPrompt,
                           int turn, int maxTurns, boolean ended, String ending, List<CharacterView> characters,
                           SceneEngine.State scene, List<Turn> history) {}
    public record CharacterProfile(String id, String name, String intro, String personality) {}
    public record Story(String title, String opening, OpeningPrompt openingPrompt, String premise, int maxTurns,
                        List<SceneEngine.Plan> scenes, List<CharacterProfile> characters) {}
}
