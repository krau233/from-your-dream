package com.fromyourdream.backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 按作者大纲维护每局当前幕；角色发言和叙事候选通过校验后才能改变场景。 */
public class SceneEngine {

    private static final Logger log = LoggerFactory.getLogger(SceneEngine.class);
    private final List<Plan> plans;
    private final Map<String, String> names;
    private final int maxTurns;
    private final CharacterAgent agent;

    public SceneEngine(List<Plan> plans, Map<String, String> names, int maxTurns, CharacterAgent agent) {
        this.names = Map.copyOf(names);
        this.maxTurns = maxTurns;
        this.agent = agent;
        if (plans == null || plans.isEmpty() || plans.stream().anyMatch(plan -> plan == null
                || !text(plan.id(), 64) || !text(plan.title(), 100) || !text(plan.environment(), 1200)
                || !text(plan.goal(), 1000) || !text(plan.exitCondition(), 1000)
                || plan.maxTurns() < 1 || plan.maxTurns() > maxTurns
                || !validParticipants(plan.characterIds(), names.keySet()))) {
            throw new IllegalStateException("剧情大纲需要有效的场景、环境、参与角色、目标、结束条件和互动预算");
        }
        if (plans.stream().map(Plan::id).distinct().count() != plans.size()
                || plans.stream().mapToInt(Plan::maxTurns).sum() > maxTurns) {
            throw new IllegalStateException("场景 ID 不能重复，场景互动预算总和不能超过故事上限");
        }
        this.plans = List.copyOf(plans);
    }

    public State initialState() {
        Plan first = plans.get(0);
        return new State(first.id(), first.title(), first.environment(), List.copyOf(first.characterIds()), 0, false);
    }

    public String context(State state) {
        Plan plan = plan(state.id());
        return "当前幕：" + state.id() + " / " + state.title()
                + "\n当前环境：" + state.environment()
                + "\n当前在场角色：" + participants(state.presentCharacterIds())
                + "\n本幕关注的问题：" + plan.goal()
                + "\n本幕结束条件：" + plan.exitCondition();
    }

    public List<String> responders(State current, String perceptions, String input) {
        if (current.presentCharacterIds().isEmpty()) return List.of();
        var responsePlan = agent.planResponses(current.presentCharacterIds(), context(current)
                + "\n各在场角色的设定和各自可知的经历：\n" + perceptions, input);
        if (responsePlan == null || !validParticipants(responsePlan.speakerIds(),
                new HashSet<>(current.presentCharacterIds()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "场景回应名单无效，本轮未保存，请重试。");
        }
        return List.copyOf(responsePlan.speakerIds());
    }

    public Resolution resolve(String story, State current, int number, String history,
                              Map<String, String> memories, boolean endConversation) {
        Plan currentPlan = plan(current.id());
        int index = plans.indexOf(currentPlan);
        Plan next = index + 1 < plans.size() ? plans.get(index + 1) : null;
        boolean atLimit = current.turns() + 1 >= currentPlan.maxTurns();
        String participantMemories = current.presentCharacterIds().stream()
                .map(id -> names.get(id) + "（" + id + "）的独立记忆：" + memories.getOrDefault(id, "尚无额外记忆。"))
                .collect(Collectors.joining("\n"));
        String advanceContext = context(current)
                + "\n全局第 " + number + "/" + maxTurns + " 次关键决定；本幕第 " + (current.turns() + 1)
                + "/" + currentPlan.maxTurns() + " 次关键决定。日常对白和动作连续演绎，不单独消耗决定次数。"
                + "\n本幕是否已达到必须收束的时间上限：" + atLimit
                + "\n玩家是否要求暂时结束交谈并继续剧情：" + endConversation
                + "\n若本幕继续，targetSceneId 必须为：" + current.id()
                + (next == null
                    ? "\n这是最后一幕；若本幕结束，targetSceneId 仍为 " + current.id() + "，随后会单独生成结局。"
                    : "\n若本幕结束，必须按大纲进入：" + next.id() + " / " + next.title()
                        + "\n下一幕环境基线：" + next.environment()
                        + "\n下一幕要处理：" + next.goal()
                        + "\n下一幕可在场角色：" + participants(next.characterIds()))
                + "\n本幕可在场角色：" + participants(currentPlan.characterIds())
                + "\n本轮开始时所有在场者的独立记忆：\n" + participantMemories
                + "\nmemories 的键必须恰好为：" + current.presentCharacterIds()
                + "；名单为空时必须返回空对象 {}。本轮结束后才入场的人不放入本轮 memories。"
                + "\n已经实际发生的事件与关键决定（包含本轮所有公开回应，按见证者区分认知）：\n" + history;
        var beat = agent.advance(story, advanceContext);
        for (int attempt = 0; ; attempt++) {
            String issue = invalidReason(beat, current, next, atLimit);
            if (issue.isEmpty()) break;
            log.warn("场景结算校验失败 scene={} attempt={} reason={}", current.id(), attempt + 1, issue);
            if (attempt == 1) throw invalid();
            beat = agent.advance(story, advanceContext
                    + "\n上一版候选未通过校验，尚未保存，也不属于已经发生的历史。保持同一玩家选择，修正以下问题，返回完整结算结果。"
                    + "\n校验问题：" + issue + "\n上一版候选：\n" + beat);
        }
        Plan target = beat.sceneEnded() && next != null ? next : currentPlan;
        boolean terminal = beat.sceneEnded() && next == null;
        State updated = new State(target.id(), target.title(), beat.environment().trim(),
                List.copyOf(beat.presentCharacterIds()), target == currentPlan ? current.turns() + 1 : 0, terminal);
        return new Resolution(updated, beat.narration().trim(), beat.sceneEnded(),
                beat.memories().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().trim())));
    }

    private String invalidReason(CharacterAgent.StoryBeat beat, State current, Plan next, boolean atLimit) {
        if (beat == null) return "没有返回结算对象。";
        if (atLimit && !beat.sceneEnded()) {
            return "本幕已达到上限，sceneEnded 必须为 true，targetSceneId 必须为 "
                    + (next == null ? current.id() : next.id()) + "。";
        }
        Plan target = beat.sceneEnded() && next != null ? next : plan(current.id());
        if (!target.id().equals(beat.targetSceneId())) {
            return "targetSceneId 应为 " + target.id() + "，实际为 " + beat.targetSceneId() + "。";
        }
        if (!optionalText(beat.narration(), 1800)) return "narration 必须是最多 1800 字的字符串。";
        if (!text(beat.environment(), 1200)) return "environment 必须是 1 至 1200 字的字符串。";
        if (!validParticipants(beat.presentCharacterIds(), new HashSet<>(target.characterIds()))) {
            return "presentCharacterIds 只能从 " + target.characterIds() + " 中选择且不能重复，实际为 "
                    + beat.presentCharacterIds() + "。";
        }
        if (beat.memories() == null
                || !beat.memories().keySet().equals(new HashSet<>(current.presentCharacterIds()))) {
            return "memories 的键必须恰好为推进前的 " + current.presentCharacterIds()
                    + "，实际为 " + (beat.memories() == null ? "null" : beat.memories().keySet())
                    + "；推进前无人时返回 {}，新入场者不在本轮记忆更新中。";
        }
        if (beat.memories().values().stream().anyMatch(value -> !text(value, 1600))) {
            return "每份角色记忆必须是 1 至 1600 字的字符串。";
        }
        if ((beat.sceneEnded() || !new HashSet<>(beat.presentCharacterIds()).equals(new HashSet<>(current.presentCharacterIds())))
                && beat.narration().isBlank()) {
            return "转场或参与名单改变时，narration 必须交代实际经过，不能为空。";
        }
        return "";
    }

    private Plan plan(String id) {
        return plans.stream().filter(plan -> plan.id().equals(id)).findFirst().orElseThrow();
    }

    private String participants(List<String> ids) {
        return ids.isEmpty() ? "只有玩家" : ids.stream().map(id -> names.get(id) + "（" + id + "）")
                .collect(Collectors.joining("、"));
    }

    private static boolean validParticipants(List<String> ids, Set<String> allowed) {
        return ids != null && ids.stream().noneMatch(Objects::isNull)
                && new HashSet<>(ids).size() == ids.size() && allowed.containsAll(ids);
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static boolean optionalText(String value, int limit) {
        return value != null && value.length() <= limit;
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "剧情生成未通过场景检查，自动修正仍未成功。本轮未保存，可以重试同一选项。");
    }

    public record Plan(String id, String title, String environment, List<String> characterIds,
                       String goal, String exitCondition, int maxTurns) {}
    public record State(String id, String title, String environment, List<String> presentCharacterIds,
                        int turns, boolean ended) {}
    public record Resolution(State scene, String narration, boolean sceneEnded, Map<String, String> memories) {}
}
