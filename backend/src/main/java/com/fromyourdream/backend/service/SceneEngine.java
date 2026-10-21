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

/** 每局维护可修订的大纲；一幕按剧情里程碑结束，可以包含多次关键决定。 */
public class SceneEngine {

    private static final Logger log = LoggerFactory.getLogger(SceneEngine.class);
    private final Outline initialOutline;
    private final Map<String, String> names;
    private final Set<String> allowedActors;
    private final CharacterAgent agent;

    public SceneEngine(Outline initialOutline, Map<String, String> names, CharacterAgent agent) {
        this.names = Map.copyOf(names);
        this.agent = agent;
        String issue = outlineIssue(initialOutline, names.keySet(), Set.of(), List.of(), false);
        if (!issue.isEmpty()) throw new IllegalStateException("初始大纲无效：" + issue);
        this.initialOutline = copy(initialOutline);
        this.allowedActors = initialOutline.milestones().stream().flatMap(plan -> plan.characterIds().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Outline initialOutline() {
        return initialOutline;
    }

    public State initialState() {
        Plan first = initialOutline.milestones().get(0);
        return new State(first.id(), first.title(), first.environment(), first.characterIds(), 0, false);
    }

    public String context(State state, Outline outline) {
        Plan plan = currentPlan(state, outline);
        return "当前剧情里程碑：" + state.id() + " / " + state.title()
                + "\n当前实际环境：" + state.environment()
                + "\n当前可参与互动的角色：" + participants(state.presentCharacterIds())
                + "\n本幕关注的问题：" + plan.goal()
                + "\n本幕里程碑结束条件：" + plan.exitCondition()
                + "\n本幕已作出 " + state.turns() + " 次关键决定。次数只作记录，不构成结束条件。";
    }

    public String outlineContext(Outline outline) {
        return "后续发展预案（不是已经发生的事实）：" + outline.development()
                + "\n当前预计结局方向（可以随实际结果修订）：" + outline.endingDirection()
                + "\n待处理里程碑：\n" + outline.milestones().stream().map(plan ->
                    plan.id() + " / " + plan.title() + "\n预计环境：" + plan.environment()
                    + "\n可参与角色：" + participants(plan.characterIds()) + "\n发展目标：" + plan.goal()
                    + "\n结束条件：" + plan.exitCondition()).collect(Collectors.joining("\n\n"));
    }

    public List<String> responders(State current, Outline outline, String perceptions, String input) {
        if (current.presentCharacterIds().isEmpty()) return List.of();
        var responsePlan = agent.planResponses(current.presentCharacterIds(), context(current, outline)
                + "\n各角色的设定和各自可知的经历：\n" + perceptions, input);
        if (responsePlan == null || !validParticipants(responsePlan.speakerIds(),
                new HashSet<>(current.presentCharacterIds()))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "场景回应名单无效，本轮未保存，请重试。");
        }
        return List.copyOf(responsePlan.speakerIds());
    }

    public Resolution resolve(String story, State current, Outline outline, List<Revision> revisions,
                              int number, String history, Map<String, String> memories) {
        Plan currentPlan = currentPlan(current, outline);
        String participantMemories = current.presentCharacterIds().stream()
                .map(id -> names.get(id) + "（" + id + "）的独立记忆：" + memories.getOrDefault(id, "尚无额外记忆。"))
                .collect(Collectors.joining("\n"));
        String advanceContext = context(current, outline)
                + "\n这是全局第 " + number + " 次关键决定。没有按选择次数强制转幕或结束全局的规则。"
                + "\n只结算当前幕的实际进展。未达到里程碑时继续本幕；里程碑结束后另由导演修订大纲，不在本轮预演下一幕。"
                + "\n本幕可参与角色：" + participants(currentPlan.characterIds())
                + "\n本轮开始时所有参与者的独立记忆：\n" + participantMemories
                + "\nmemories 的键必须恰好为：" + current.presentCharacterIds()
                + "；名单为空时返回 {}，本轮结束后才入场者不放入本轮 memories。"
                + "\n导演当前预案：\n" + outlineContext(outline)
                + "\n已经实际发生的事件与关键决定：\n" + history;
        var beat = agent.advance(story, advanceContext);
        for (int attempt = 0; ; attempt++) {
            String issue = beatIssue(beat, current, currentPlan);
            if (issue.isEmpty()) break;
            log.warn("里程碑结算校验失败 milestone={} attempt={} reason={}", current.id(), attempt + 1, issue);
            if (attempt == 1) throw invalid("剧情结算");
            beat = agent.advance(story, advanceContext
                    + "\n上一版候选尚未保存，不是已发生的历史。保留玩家决定，按校验问题修正并返回完整结果。"
                    + "\n校验问题：" + issue + "\n上一版候选：\n" + beat);
        }
        Outline updatedOutline = outline;
        Revision revision = null;
        if (beat.milestoneEnded()) {
            Set<String> completedIds = revisions.stream().map(Revision::milestoneId).collect(Collectors.toSet());
            completedIds.add(current.id());
            String revisionContext = "上一版完整预案：\n" + outlineContext(outline)
                    + "\n此前已经完成的里程碑记录（不可改写）：\n" + revisions.stream().map(item ->
                        item.milestoneId() + "：" + item.outcome() + "；实际影响：" + item.impact())
                        .collect(Collectors.joining("\n"))
                    + "\n本幕：" + current.id() + " / " + current.title()
                    + "\n本幕实际结果：" + beat.outcome() + "\n本幕实际影响与未完成事项：" + beat.impact()
                    + "\n结算后实际环境：" + beat.environment()
                    + "\n当前仍可参与互动的角色 ID：" + beat.presentCharacterIds()
                    + "\n全局是否已由实际事件结束：" + beat.storyEnded() + "\n结束依据：" + beat.endingReason()
                    + "\n已完成 ID（不能重新使用）：" + completedIds
                    + "\n未来里程碑可使用的角色 ID：" + allowedActors
                    + "\n已保存经历及本轮决定：\n" + history + "\n本轮最终演绎：\n" + beat.narration();
            var candidate = agent.reviseOutline(story, revisionContext);
            for (int attempt = 0; ; attempt++) {
                String issue = outlineIssue(candidate, allowedActors, completedIds,
                        beat.presentCharacterIds(), beat.storyEnded());
                if (issue.isEmpty()) break;
                log.warn("大纲修订校验失败 milestone={} attempt={} reason={}", current.id(), attempt + 1, issue);
                if (attempt == 1) throw invalid("后续大纲修订");
                candidate = agent.reviseOutline(story, revisionContext
                        + "\n上版修订尚未保存。校验问题：" + issue + "\n上版候选：\n" + candidate);
            }
            updatedOutline = copy(candidate);
            revision = new Revision(revisions.size() + 1, current.id(), current.title(),
                    number - current.turns(), number, beat.outcome().trim(), beat.impact().trim(), updatedOutline);
        }
        Plan target = beat.milestoneEnded() && !beat.storyEnded() ? updatedOutline.milestones().get(0) : currentPlan;
        State updated = new State(target.id(), target.title(), beat.environment().trim(),
                List.copyOf(beat.presentCharacterIds()), target == currentPlan ? current.turns() + 1 : 0, beat.storyEnded());
        return new Resolution(updated, beat.narration().trim(), beat.milestoneEnded(),
                beat.memories().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().trim())), updatedOutline, revision);
    }

    private String beatIssue(CharacterAgent.StoryBeat beat, State current, Plan plan) {
        if (beat == null) return "没有返回结算对象。";
        if (!optionalText(beat.narration(), 2400)) return "narration 必须是最多 2400 字的字符串。";
        if (!text(beat.environment(), 1200)) return "environment 必须是 1 至 1200 字的字符串。";
        if (!validParticipants(beat.presentCharacterIds(), new HashSet<>(plan.characterIds()))) {
            return "presentCharacterIds 只能从本幕的 " + plan.characterIds() + " 中选择且不能重复。";
        }
        if (beat.memories() == null
                || !beat.memories().keySet().equals(new HashSet<>(current.presentCharacterIds()))) {
            return "memories 的键必须恰好为 " + current.presentCharacterIds()
                    + "，实际为 " + (beat.memories() == null ? "null" : beat.memories().keySet())
                    + "；推进前无人时返回 {}，新入场者不在本轮更新中。";
        }
        if (beat.memories().values().stream().anyMatch(value -> !text(value, 1600))) {
            return "每份角色记忆必须是 1 至 1600 字的字符串。";
        }
        if (beat.milestoneEnded() && (!text(beat.outcome(), 1200) || !text(beat.impact(), 1200))) {
            return "里程碑结束时，outcome 和 impact 必须记录实际结果、决定的影响及未完成事项。";
        }
        if (beat.storyEnded() && (!beat.milestoneEnded() || !text(beat.endingReason(), 1200))) {
            return "全局结束时必须同时结束里程碑，并在 endingReason 给出本局实际成立的终局依据。";
        }
        if ((beat.milestoneEnded() || !new HashSet<>(beat.presentCharacterIds()).equals(new HashSet<>(current.presentCharacterIds())))
                && beat.narration().isBlank()) {
            return "里程碑结束或参与名单改变时，narration 必须交代实际经过。";
        }
        return "";
    }

    private static String outlineIssue(Outline outline, Set<String> actors, Set<String> completed,
                                       List<String> present, boolean ended) {
        if (outline == null || !text(outline.development(), 2400) || !text(outline.endingDirection(), 2400)
                || outline.milestones() == null || outline.milestones().size() > 20) {
            return "需要 development、endingDirection 与 milestones；文字各不超过 2400 字，一次修订最多 20 个待处理里程碑。";
        }
        if (ended != outline.milestones().isEmpty()) {
            return ended ? "全局已经结束，milestones 必须为空，不再规划未发生的行动。"
                    : "全局尚未结束，至少保留一个待处理里程碑，不能仅因旧计划用完就结束故事。";
        }
        Set<String> ids = new HashSet<>();
        for (Plan plan : outline.milestones()) {
            if (plan == null || plan.id() == null || !plan.id().matches("[a-zA-Z0-9-]{1,64}")
                    || !text(plan.title(), 100) || !text(plan.environment(), 1200)
                    || !text(plan.goal(), 1200) || !text(plan.exitCondition(), 1200)
                    || !validParticipants(plan.characterIds(), actors)) {
                return "每个里程碑需要有效且唯一的 id、title、environment、goal、exitCondition 和允许的 characterIds。";
            }
            if (completed.contains(plan.id()) || !ids.add(plan.id())) return "不能重复 ID 或重开已完成的里程碑：" + plan.id();
        }
        if (!ended && !outline.milestones().get(0).characterIds().containsAll(present)) {
            return "下一里程碑须允许当前仍在互动的 " + present + "，不能在改大纲时悄悄移除角色。";
        }
        return "";
    }

    private static Outline copy(Outline outline) {
        return new Outline(outline.development().trim(), outline.endingDirection().trim(),
                outline.milestones().stream().map(plan -> new Plan(plan.id(), plan.title().trim(),
                        plan.environment().trim(), List.copyOf(plan.characterIds()), plan.goal().trim(),
                        plan.exitCondition().trim())).toList());
    }

    private Plan currentPlan(State state, Outline outline) {
        if (outline.milestones().isEmpty() || !outline.milestones().get(0).id().equals(state.id())) {
            throw new IllegalStateException("当前里程碑与本局大纲不一致");
        }
        return outline.milestones().get(0);
    }

    private String participants(List<String> ids) {
        return ids.isEmpty() ? "无" : ids.stream().map(id -> names.get(id) + "（" + id + "）")
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

    private ResponseStatusException invalid(String stage) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                stage + "未通过检查，自动修正仍未成功。本轮未保存，可以重试同一选项。");
    }

    public record Plan(String id, String title, String environment, List<String> characterIds,
                       String goal, String exitCondition) {}
    public record Outline(String development, String endingDirection, List<Plan> milestones) {}
    public record Revision(int number, String milestoneId, String milestoneTitle, int firstTurn, int lastTurn,
                           String outcome, String impact, Outline plan) {}
    public record State(String id, String title, String environment, List<String> presentCharacterIds,
                        int turns, boolean ended) {}
    public record Resolution(State scene, String narration, boolean milestoneEnded, Map<String, String> memories,
                             Outline outline, Revision revision) {}
}
