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

import com.fromyourdream.backend.service.CharacterAgent.Choice;
import com.fromyourdream.backend.service.CharacterAgent.DecisionPoint;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DecisionPoint openingDecision;
    // ponytail: 单实例内存原型；重启清空进度，需要持久化时替换这里的存储。
    private final Map<String, Slot> games = new ConcurrentHashMap<>();

    @Autowired
    public GameService(RedisService accounts, CharacterAgent agent) throws IOException {
        this(accounts, agent, loadStory());
    }

    private static Story loadStory() throws IOException {
        try (var input = new ClassPathResource("demo-story.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(input, Story.class);
        }
    }

    GameService(RedisService accounts, CharacterAgent agent, Story story) {
        this.accounts = accounts;
        this.agent = agent;
        this.story = story;
        if (story.characters() == null || story.characters().isEmpty()) {
            throw new IllegalStateException("故事需要角色设定与初始剧情大纲");
        }
        scenes = new SceneEngine(story.outline(), story.characters().stream().collect(
                Collectors.toMap(CharacterProfile::id, CharacterProfile::name)), agent);
        OpeningPrompt opening = story.openingPrompt();
        if (!text(story.opening(), 2000) || opening == null
                || !scenes.initialState().presentCharacterIds().contains(opening.characterId())
                || !text(opening.dialogue(), 1000)) {
            throw new IllegalStateException("示例故事需要开场描写和在场角色的开场台词");
        }
        openingDecision = validateDecision(story.openingDecision());
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
                || request.message().length() > 1000
                || (request.choiceId().isEmpty() && request.message().isBlank())
                || (!request.choiceId().isEmpty() && (!request.choiceId().matches("[a-zA-Z0-9-]{1,64}")
                    || !request.message().isBlank()))) {
            throw error(HttpStatus.BAD_REQUEST, "请选择一个关键选项，或输入 1 至 1000 字的其他决定。");
        }
        if (request.endConversation()) {
            throw error(HttpStatus.BAD_REQUEST, "关键决定需要你来选择，日常对白会自动演绎。");
        }
        Slot slot = slot(code);
        acquire(slot);
        try {
            Snapshot current = slot.snapshot;
            requireGame(current, request.gameId());
            for (Turn previous : current.turns()) {
                if (previous.requestId().equals(request.requestId())) {
                    String previousChoiceId = previous.selectedChoice() == null ? "" : previous.selectedChoice().id();
                    if (!previousChoiceId.equals(request.choiceId())
                            || (previousChoiceId.isEmpty() && !previous.playerInput().equals(request.message().trim()))
                            || previous.endConversation() != request.endConversation()) {
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
            Choice selected = request.choiceId().isEmpty() ? null : current.decision().choices().stream()
                    .filter(choice -> choice.id().equals(request.choiceId())).findFirst()
                    .orElseThrow(() -> error(HttpStatus.CONFLICT, "当前选项已变化，请刷新后重新选择。"));
            String input = selected == null ? request.message().trim() : selected.action();
            int number = current.turns().size() + 1;
            List<String> witnesses = List.copyOf(current.scene().presentCharacterIds());
            var responses = new ArrayList<CharacterResponse>();
            var memories = new HashMap<>(current.memories());
            var perceptions = new HashMap<String, String>();
            for (String id : witnesses) perceptions.put(id, context(current, character(id), number));
            String candidateContext = witnesses.stream().map(id -> character(id).personality()
                    + "\n该角色可知的经历：\n" + perceptions.get(id)).collect(Collectors.joining("\n\n"));
            for (String id : scenes.responders(current.scene(), current.outline(), candidateContext, input)) {
                CharacterProfile character = character(id);
                String context = perceptions.get(id)
                        + "\n本轮玩家发言后，在你之前已经发生的公开回应：\n"
                        + (responses.isEmpty() ? "暂时没有其他人的可观察回应。" : responseTranscript(responses));
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
                    List.copyOf(responses), "", false, current.scene().id(), false, Instant.now(),
                    current.decision().question(), selected);
            turns.add(turn);
            String sceneHistory = openingTranscript() + "\n\n" + transcript(turns);
            var resolution = scenes.resolve(storyBoundary(), current.scene(), current.outline(), current.revisions(),
                    number, sceneHistory, memories);
            memories.putAll(resolution.memories());
            turns.set(turns.size() - 1, new Turn(number, request.requestId(), input, current.scene().id(), witnesses,
                    List.copyOf(responses), resolution.narration(), resolution.milestoneEnded(), resolution.scene().id(),
                    false, turn.createdAt(), current.decision().question(), selected));
            var revisions = new ArrayList<>(current.revisions());
            if (resolution.revision() != null) revisions.add(resolution.revision());
            String ending = "";
            DecisionPoint decision = null;
            if (resolution.scene().ended()) {
                ending = agent.conclude(storyBoundary(), scenes.outlineContext(resolution.outline())
                        + "\n按实际终局修订的结果：" + resolution.revision().outcome()
                        + "\n实际代价与遗留问题：" + resolution.revision().impact()
                        + "\n已经发生的完整历史：\n" + openingTranscript() + "\n\n" + transcript(turns));
                if (!text(ending, 6000)) throw error(HttpStatus.BAD_GATEWAY, "结局内容无效，本轮未保存。");
                ending = ending.trim();
            } else {
                decision = validateDecision(agent.prepareDecision(storyBoundary(), scenes.context(resolution.scene(), resolution.outline())
                        + "\n已经发生的完整经历与决定（不可重置或换个措辞重复询问）：\n"
                        + openingTranscript() + "\n\n" + transcript(turns)));
            }
            // 演出、幕末大纲修订、下一组关键选项或结局全部成功后，才一起提交。
            slot.snapshot = new Snapshot(current.gameId(), List.copyOf(turns), Map.copyOf(memories),
                    resolution.scene(), ending, decision, resolution.outline(), List.copyOf(revisions));
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
            slot.snapshot = Snapshot.empty(scenes.initialState(), openingDecision, scenes.initialOutline());
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
                + "\n当前是第 " + number + " 次关键决定。\n" + scenes.context(snapshot.scene(), snapshot.outline())
                + "\n眼前需要玩家作出关键决定的问题：" + snapshot.decision().question()
                + "\n按故事规定的渠道接收信息。同场公开发言可被在场者听见；跨屏幕、电话或门两侧，只能知道实际通过该渠道传来的内容。"
                + "\n记录同时描述了玩家其他地方的行动，不代表你亲眼看见或已被告知。大纲目标与结束条件是作者计划，不是你已经知道的事实。"
                + "\n你只知道自己实际可观察的环境、自己的记忆和亲历记录，离场期间及其他世界未传来的信息不知情。"
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
                + "，在场：" + participantNames(turn.witnessIds()) + "]\n当时的关键分歧：" + turn.decisionQuestion()
                + (turn.selectedChoice() == null ? "" : "\n玩家选择：" + turn.selectedChoice().label()
                    + "\n玩家接受的取舍与风险：" + turn.selectedChoice().stakes())
                + "\n玩家的实际决定：" + turn.playerInput()
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
        OpeningPrompt opening = story.openingPrompt();
        return new GameView(code, snapshot.gameId(), story.title(), story.opening(), new OpeningPrompt(
                opening.characterId(), opening.dialogue(), openingDecision.choices().stream().map(Choice::action).toList()),
                snapshot.turns().size(), snapshot.revisions().size(), !snapshot.ending().isEmpty(), snapshot.ending(),
                story.characters().stream().map(item -> new CharacterView(item.id(), item.name(), item.intro())).toList(),
                snapshot.scene(), snapshot.turns(), snapshot.decision());
    }

    private String storyBoundary() {
        return story.premise()
                + (story.directorNotes() == null ? "" : "\n仅导演可知的设定与揭示顺序，未经现场证据确认不能公开：\n" + story.directorNotes())
                + "\n角色设定：\n" + story.characters().stream()
                .map(CharacterProfile::personality).collect(Collectors.joining("\n"));
    }

    private DecisionPoint validateDecision(DecisionPoint decision) {
        if (decision == null || !text(decision.question(), 500) || decision.choices() == null
                || decision.choices().size() < 2 || decision.choices().size() > 4
                || decision.choices().stream().anyMatch(choice -> choice == null || choice.id() == null
                    || !choice.id().matches("[a-zA-Z0-9-]{1,64}") || !text(choice.label(), 200)
                    || !text(choice.action(), 1000) || !text(choice.stakes(), 400))
                || decision.choices().stream().map(Choice::id).distinct().count() != decision.choices().size()
                || decision.choices().stream().map(Choice::label).distinct().count() != decision.choices().size()
                || decision.choices().stream().map(Choice::action).distinct().count() != decision.choices().size()
                || decision.choices().stream().map(Choice::stakes).distinct().count() != decision.choices().size()) {
            throw error(HttpStatus.BAD_GATEWAY, "关键选项内容无效，本轮未保存，请重试。");
        }
        return new DecisionPoint(decision.question().trim(), List.copyOf(decision.choices()));
    }

    private String accountCode(String userId) {
        String code = userId == null ? "" : userId.trim();
        if (code.startsWith("user_")) code = code.substring(5);
        if (!code.matches("[a-zA-Z0-9]{1,8}")) {
            throw error(HttpStatus.BAD_REQUEST, "账号 ID 应为 1 至 8 位字母或数字，也可带 user_ 前缀。");
        }
        try {
            if (!accounts.userExists(code)) throw error(HttpStatus.NOT_FOUND, "账号不存在，请先生成账号 ID。");
        } catch (DataAccessException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "账号服务暂不可用，请稍后重试。");
        }
        return code;
    }

    private Slot slot(String code) {
        return games.computeIfAbsent(code, ignored -> new Slot(scenes.initialState(), openingDecision, scenes.initialOutline()));
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
        Slot(SceneEngine.State scene, DecisionPoint decision, SceneEngine.Outline outline) {
            snapshot = Snapshot.empty(scene, decision, outline);
        }
    }

    private record Snapshot(String gameId, List<Turn> turns, Map<String, String> memories,
                            SceneEngine.State scene, String ending, DecisionPoint decision,
                            SceneEngine.Outline outline, List<SceneEngine.Revision> revisions) {
        static Snapshot empty(SceneEngine.State scene, DecisionPoint decision, SceneEngine.Outline outline) {
            return new Snapshot(UUID.randomUUID().toString(), List.of(), Map.of(), scene, "", decision, outline, List.of());
        }
    }

    public record TurnRequest(String gameId, String requestId, int expectedTurn, String message,
                              Boolean endConversation, String choiceId) {
        public TurnRequest {
            endConversation = Boolean.TRUE.equals(endConversation);
            message = message == null ? "" : message;
            choiceId = choiceId == null ? "" : choiceId.trim();
        }
        public TurnRequest(String gameId, String requestId, int expectedTurn, String message, Boolean endConversation) {
            this(gameId, requestId, expectedTurn, message, endConversation, "");
        }
    }
    public record CharacterResponse(String characterId, String characterName, String narration, String dialogue,
                                    String emotion) {}
    public record Turn(int number, String requestId, String playerInput, String sceneId, List<String> witnessIds,
                       List<CharacterResponse> responses, String storyNarration, boolean sceneEnded,
                       String nextSceneId, boolean endConversation, Instant createdAt,
                       String decisionQuestion, Choice selectedChoice) {}
    public record OpeningPrompt(String characterId, String dialogue, List<String> suggestedReplies) {}
    public record CharacterView(String id, String name, String intro) {}
    public record GameView(String userId, String gameId, String title, String opening, OpeningPrompt openingPrompt,
                           int turn, int completedMilestones, boolean ended, String ending, List<CharacterView> characters,
                           SceneEngine.State scene, List<Turn> history, DecisionPoint decision) {}
    public record CharacterProfile(String id, String name, String intro, String personality) {}
    public record Story(String title, String opening, OpeningPrompt openingPrompt, String premise, SceneEngine.Outline outline,
                        List<CharacterProfile> characters,
                        DecisionPoint openingDecision, String directorNotes) {}
}
