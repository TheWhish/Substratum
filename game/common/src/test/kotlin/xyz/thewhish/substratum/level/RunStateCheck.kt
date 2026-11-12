package xyz.thewhish.substratum.level

import java.util.UUID

private val HOST = UUID(0, 1)
private val FRIEND = UUID(0, 2)
private val THIRD = UUID(0, 3)
private val STRANGER = UUID(0, 4)

fun main() {
    midLevelJoinWatchesUntilTheTeamMovesOn()
    transitionSavesEveryoneAndRevivesWatchers()
    reopeningStartsTheLevelFromTheSave()
    lastDeathRestartsTheLevelEmptyHanded()
    leavingAliveKeepsItemsWhileTheWorldIsOpen()
    newcomersStartEmptyHanded()
    onlyTransitionsTouchTheSave()
    soloFollowsTheSameRules()
    theDyingStayOnAirUntilTheyFall()
    println("run: every row of the run table holds")
}

private fun started(level: Int = 0, save: Map<UUID, String> = emptyMap()): RunState<String> =
    RunState<String>().apply {
        load(level, save)
        check(begin()) { "a loaded run must begin once" }
        check(!begin()) { "a run must not begin twice" }
    }

private fun midLevelJoinWatchesUntilTheTeamMovesOn() {
    val run = started()
    run.join(THIRD)
    check(run.isLost(THIRD)) { "a player who joins mid-level must watch" }
    run.leave(THIRD, "nothing")
    run.join(THIRD)
    check(run.isLost(THIRD)) { "a player who drops and returns must watch again, with no grace window" }
    run.advance(mapOf(HOST to "lamp", THIRD to "nothing"))
    check(!run.isLost(THIRD)) { "a watcher must revive on the next level" }
    run.join(STRANGER)
    run.wipe()
    check(!run.isLost(STRANGER)) { "a watcher must revive when the level restarts" }
}

private fun transitionSavesEveryoneAndRevivesWatchers() {
    val run = started()
    run.lose(FRIEND)
    val area = run.area
    run.advance(mapOf(HOST to "water", FRIEND to "empty"))
    check(run.level == 1) { "a transition must move to the next level" }
    check(run.area != area) { "a transition must open a new area" }
    check(!run.isLost(FRIEND)) { "the dead must revive on the next level" }
    check(run.save == mapOf(HOST to "water", FRIEND to "empty")) { "a transition must save every player, got ${run.save}" }
}

private fun reopeningStartsTheLevelFromTheSave() {
    val save = mapOf(HOST to "water", FRIEND to "lamp")
    val run = started(level = 3, save = save)
    check(run.level == 3) { "a reopened run must stay on its level" }
    check(run.entry(HOST) == "water") { "the host must start with the saved items" }
    check(run.join(FRIEND) == "lamp") { "a teammate joining after the start must get the saved items, not the old player data" }
}

private fun lastDeathRestartsTheLevelEmptyHanded() {
    val run = started(save = mapOf(HOST to "water"))
    run.leave(THIRD, "lamp")
    check(run.lose(HOST) && run.lose(FRIEND)) { "the living must be able to die" }
    check(!run.lose(HOST)) { "a death must count once" }
    check(run.fallen(listOf(HOST, FRIEND))) { "the team falls when every online player is dead" }
    val area = run.area
    run.wipe()
    check(run.area != area) { "a restart must open a new area" }
    check(!run.fallen(listOf(HOST, FRIEND))) { "everyone must be alive after the restart" }
    check(run.join(THIRD) == null) { "a teammate away during the wipe must come back empty-handed" }
    check(run.level == 0) { "a restart must stay on the same level" }
}

private fun leavingAliveKeepsItemsWhileTheWorldIsOpen() {
    val run = started()
    run.leave(FRIEND, "lamp")
    check(run.join(FRIEND) == "lamp") { "a player who left alive must keep the items while the world is open" }
    run.leave(FRIEND, "lamp")
    run.advance(mapOf(HOST to "water"))
    check(run.save[FRIEND] == "lamp") { "a player away during a transition must be saved with the items they left with" }
}

private fun newcomersStartEmptyHanded() {
    val run = started(save = mapOf(HOST to "water"))
    check(run.join(STRANGER) == null) { "a newcomer must start empty-handed" }
    check(run.entry(STRANGER) == null) { "a newcomer must not inherit anyone's save" }
}

private fun onlyTransitionsTouchTheSave() {
    val save = mapOf(HOST to "water")
    val run = started(save = save)
    run.leave(FRIEND, "lamp")
    run.lose(HOST)
    run.join(THIRD)
    run.wipe()
    check(run.save == save) { "only a transition may change the save, got ${run.save}" }
}

private fun theDyingStayOnAirUntilTheyFall() {
    val run = started()
    check(run.lose(HOST, until = 100)) { "the living must be able to start dying" }
    check(run.isLost(HOST) && run.isDying(HOST)) { "a dying player is already lost but still falling" }
    check(run.watchable(HOST)) { "the team must keep watching a falling player" }
    check(!run.fallen(listOf(HOST))) { "the level must not restart before the last fall ends" }
    check(run.expire(99).isEmpty()) { "a fall must not end early" }
    check(run.expire(100) == listOf(HOST)) { "a fall must end on time" }
    check(!run.isDying(HOST) && !run.watchable(HOST)) { "a finished fall leaves only a watcher" }
    check(run.fallen(listOf(HOST))) { "the level restarts once the last fall ends" }
    check(run.lose(FRIEND, until = 50)) { "a second player must be able to start dying" }
    run.leave(FRIEND, "lamp")
    check(!run.isDying(FRIEND) && run.expire(1_000).isEmpty()) { "leaving mid-fall must cancel the fall" }
    check(run.lose(THIRD, until = 50)) { "a third player must be able to start dying" }
    run.wipe()
    check(!run.isDying(THIRD) && run.expire(1_000).isEmpty()) { "a restart must cancel every fall" }
}

private fun soloFollowsTheSameRules() {
    val run = started()
    run.lose(HOST)
    check(run.fallen(listOf(HOST))) { "a solo death must restart the level at once" }
    run.wipe()
    check(!run.isLost(HOST)) { "the solo player must be alive after the restart" }
    check(!run.fallen(emptyList())) { "an empty server must not count as a fallen team" }
}
