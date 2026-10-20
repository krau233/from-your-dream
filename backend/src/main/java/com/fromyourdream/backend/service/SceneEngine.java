package com.fromyourdream.backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 按作者大纲维护每局当前幕；角色发言和叙事候选通过校验后才能改变场景。 */
public class SceneEngine {

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
        var beat = agent.advance(story, context(current)
                + "\n全局第 " + number + "/" + maxTurns + " 段；本幕第 " + (current.turns() + 1)
                + "/" + currentPlan.maxTurns() + " 次互动。"
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
                + "\n本幕已经实际发生的事件（包含本轮所有公开回应）：\n" + history);
        if (beat == null) throw invalid();
        Plan target = beat.sceneEnded() && next != null ? next : currentPlan;
        if (!target.id().equals(beat.targetSceneId()) || (atLimit && !beat.sceneEnded())
                || !optionalText(beat.narration(), 1800) || !text(beat.environment(), 1200)
                || !validParticipants(beat.presentCharacterIds(), new HashSet<>(target.characterIds()))
                || beat.memories() == null
                || !beat.memories().keySet().equals(new HashSet<>(current.presentCharacterIds()))
                || beat.memories().values().stream().anyMatch(value -> !text(value, 1600))) {
            throw invalid();
        }
        if ((beat.sceneEnded() || !new HashSet<>(beat.presentCharacterIds()).equals(new HashSet<>(current.presentCharacterIds())))
                && beat.narration().isBlank()) {
            throw invalid();
        }
        boolean terminal = beat.sceneEnded() && next == null;
        State updated = new State(target.id(), target.title(), beat.environment().trim(),
                List.copyOf(beat.presentCharacterIds()), target == currentPlan ? current.turns() + 1 : 0, terminal);
        return new Resolution(updated, beat.narration().trim(), beat.sceneEnded(),
                beat.memories().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().trim())));
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
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "场景结算返回内容无效，本轮未保存，请重试。");
    }

    public record Plan(String id, String title, String environment, List<String> characterIds,
                       String goal, String exitCondition, int maxTurns) {}
    public record State(String id, String title, String environment, List<String> presentCharacterIds,
                        int turns, boolean ended) {}
    public record Resolution(State scene, String narration, boolean sceneEnded, Map<String, String> memories) {}
}
