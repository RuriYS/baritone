/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathRenderer;
import baritone.utils.PathingCommandContext;
import baritone.utils.pathing.Favoring;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

import baritone.utils.pathing.PathManager;
import baritone.utils.pathing.PathStartHelper;
import net.minecraft.core.BlockPos;

public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {
    private CalculationContext calculationContext;

    // Metrics
    private int elapsedTicks;
    private BetterBlockPos initialPosition;

    // State flags
    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean wasUnpausedLastTick;
    private boolean isPausedCurrentTick;
    private boolean isCancellationRequested;
    private boolean pathCalculationFailedLastTick;
    private boolean lastAutoJump;

    // Helpers
    private final LinkedBlockingQueue<PathEvent> eventQueue = new LinkedBlockingQueue<>();
    private final PathManager pathManager = new PathManager(this);
    private final PathStartHelper pathHelper = new PathStartHelper(ctx);

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        processPathEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().terminateAllProcesses();
            return;
        }

        pathManager.setExpectedPathStart(pathStart());
        baritone.getPathingControlManager().handlePreTickProcessing();
        updatePath();
        elapsedTicks++;
        processPathEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (isPathing()) {
            event.setState(pathManager.getCurrentPath().isSprinting());
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (pathManager.getCurrentPath() != null) {
            switch (event.getState()) {
                case PRE:
                    lastAutoJump = ctx.minecraft().options.autoJump().get();
                    ctx.minecraft().options.autoJump().set(false);
                    break;
                case POST:
                    ctx.minecraft().options.autoJump().set(lastAutoJump);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }

    @Override
    public Goal getDestination() {
        return pathManager.getDestination();
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !isPausedCurrentTick;
    }

    @Override
    public PathExecutor getCurrentPath() {
        return pathManager.getCurrentPath();
    }

    @Override
    public PathExecutor getNextPlannedPath() {
        return pathManager.getNextPlannedPath();
    }

    @Override
    public Optional<AbstractNodeCostSearch> getActivePathCalculation() {
        return Optional.ofNullable(pathManager.getActivePathCalculation());
    }

    public void queuePathEvent(PathEvent event) {
        eventQueue.add(event);
    }

    private void processPathEvents() {
        ArrayList<PathEvent> currentEvents = new ArrayList<>();
        eventQueue.drainTo(currentEvents);
        pathCalculationFailedLastTick = currentEvents.contains(PathEvent.CALC_FAILED);
        for (PathEvent event : currentEvents) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    private void updatePath() {
        isPausedCurrentTick = false;

        if (isCancellationRequested) {
            isCancellationRequested = false;
            clearInputControls();
            return;
        }

        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            isPausedCurrentTick = true;
            if (wasUnpausedLastTick) {
                clearInputControls();
                BlockPos currentPos = ctx.player().blockPosition();
                pathManager.handlePauseResume(currentPos);
            }
            wasUnpausedLastTick = false;
            return;
        }

        wasUnpausedLastTick = true;

        synchronized (pathManager.getPathLock()) {
            validateActiveCalculation();
            if (pathManager.getCurrentPath() == null) {
                return;
            }
            safeToCancel = pathManager.getCurrentPath().onTick();
            handlePathCompletion();
        }
    }

    private void clearInputControls() {
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
    }

    private void validateActiveCalculation() {
        synchronized (pathManager.getPathLock()) {
            if (pathManager.getActivePathCalculation() == null) {
                return;
            }

            BetterBlockPos calculationStart = pathManager.getActivePathCalculation().getStart();
            Optional<IPath> bestPath = pathManager.getActivePathCalculation().bestPathSoFar();
            BlockPos expectedPathStart = pathManager.getExpectedPathStart();

            boolean isCalculationInvalid =
                    (pathManager.getCurrentPath() == null || !pathManager.getCurrentPath().getPath().getDest().equals(calculationStart)) &&
                            !calculationStart.equals(ctx.playerFeet()) &&
                            !calculationStart.equals(expectedPathStart) &&
                            (bestPath.isEmpty() || (!bestPath.get().positions().contains(ctx.playerFeet()) &&
                                    !bestPath.get().positions().contains(expectedPathStart)));

            if (isCalculationInvalid) {
                pathManager.getActivePathCalculation().cancel();
            }
        }
    }

    private void handlePathCompletion() {
        if (!pathManager.getCurrentPath().failed() && !pathManager.getCurrentPath().finished()) {
            handleOngoingNavigation();
            return;
        }

        if (hasReachedDestination()) {
            handleDestinationReached();
            return;
        }

        if (pathManager.getNextPlannedPath() != null && !isNextPathValid()) {
            logDebug("Discarding next path as it does not contain current position");
            queuePathEvent(PathEvent.DISCARD_NEXT);
            pathManager.setNextPlannedPath(null);
        }

        if (pathManager.getNextPlannedPath() != null) {
            continueToNextPath();
            return;
        }

        calculateNewPath();
    }

    private boolean hasReachedDestination() {
        return pathManager.getDestination() == null || pathManager.getDestination().isInGoal(ctx.playerFeet());
    }

    private void handleDestinationReached() {
        logDebug("All done. At " + pathManager.getDestination());
        queuePathEvent(PathEvent.AT_GOAL);
        pathManager.setNextPlannedPath(null);
        pathManager.setCurrentPath(null);
        if (Baritone.settings().disconnectOnArrival.value) {
            ctx.world().disconnect();
        }
    }

    private boolean isNextPathValid() {
        return pathManager.getNextPlannedPath().getPath().positions().contains(ctx.playerFeet()) ||
                pathManager.getNextPlannedPath().getPath().positions().contains(pathManager.getExpectedPathStart());
    }

    private void continueToNextPath() {
        logDebug("Continuing on to planned next path");
        queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
        pathManager.setCurrentPath(pathManager.getNextPlannedPath());
        pathManager.setNextPlannedPath(null);
        pathManager.getCurrentPath().onTick();
    }

    private void calculateNewPath() {
        synchronized (pathManager.getPathCalculationLock()) {
            if (pathManager.getActivePathCalculation() != null) {
                queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                return;
            }
            queuePathEvent(PathEvent.CALC_STARTED);
            findNewPathThreaded(pathManager.getExpectedPathStart(), true, calculationContext);
        }
    }

    private void handleOngoingNavigation() {
        if (canSwitchToNextPath()) {
            switchToNextPath();
            return;
        }

        if (Baritone.settings().splicePath.value) {
            pathManager.setCurrentPath(pathManager.getCurrentPath().trySplice(pathManager.getNextPlannedPath()));
        }

        if (pathManager.getNextPlannedPath() != null && pathManager.getCurrentPath().getPath().getDest().equals(pathManager.getNextPlannedPath().getPath().getDest())) {
            pathManager.setNextPlannedPath(null);
        }

        planAhead();
    }

    private boolean canSwitchToNextPath() {
        return isSafeToCancel() && pathManager.getNextPlannedPath() != null && pathManager.getNextPlannedPath().snipsnapifpossible();
    }

    private void switchToNextPath() {
        logDebug("Splicing into planned next path early...");
        queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
        pathManager.setCurrentPath(pathManager.getNextPlannedPath());
        pathManager.setNextPlannedPath(null);
        pathManager.setCurrentPath(pathManager.getCurrentPath());
        pathManager.getCurrentPath().onTick();
    }

    private void planAhead() {
        synchronized (pathManager.getPathCalculationLock()) {
            if (shouldStartPlanningAhead()) {
                logDebug("Path almost over. Planning ahead...");
                queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                findNewPathThreaded(pathManager.getCurrentPath().getPath().getDest(), false, calculationContext);
            }
        }
    }

    private boolean shouldStartPlanningAhead() {
        return pathManager.getActivePathCalculation() == null &&
                pathManager.getNextPlannedPath() == null &&
                pathManager.getDestination() != null &&
                !pathManager.getDestination().isInGoal(pathManager.getCurrentPath().getPath().getDest()) &&
                ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value;
    }

    public void secretInternalSetGoal(Goal goal) {
        pathManager.setDestination(goal);
    }

    public void secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        if (command instanceof PathingCommandContext) {
            calculationContext = ((PathingCommandContext) command).desiredCalcContext;
        } else {
            calculationContext = new CalculationContext(baritone, true);
        }
        if (pathManager.getDestination() == null) {
            return;
        }
        if (pathManager.getDestination().isInGoal(ctx.playerFeet()) || pathManager.getDestination().isInGoal(pathManager.getExpectedPathStart())) {
            return;
        }
        synchronized (pathManager.getPathLock()) {
            if (pathManager.getCurrentPath() != null) {
                return;
            }
            synchronized (pathManager.getPathCalculationLock()) {
                if (pathManager.getActivePathCalculation() != null) {
                    return;
                }
                queuePathEvent(PathEvent.CALC_STARTED);
                findNewPathThreaded(pathManager.getExpectedPathStart(), true, calculationContext);
            }
        }
    }

    public boolean isSafeToCancel() {
        if (pathManager.getCurrentPath() == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    public void cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
        }
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().terminateAllProcesses();
        return doIt;
    }

    public boolean calcFailedLastTick() { // NOT exposed on public api
        return pathCalculationFailedLastTick;
    }

    public void softCancelIfSafe() {
        synchronized (pathManager.getPathLock()) {
            getActivePathCalculation().ifPresent(AbstractNodeCostSearch::cancel); // only cancel ours
            if (!isSafeToCancel()) {
                return;
            }
            pathManager.setCurrentPath(null);
            pathManager.setNextPlannedPath(null);
        }
        isCancellationRequested = true;
    }

    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathManager.getPathLock()) {
            getActivePathCalculation().ifPresent(AbstractNodeCostSearch::cancel);
            if (pathManager.getCurrentPath() != null) {
                pathManager.setCurrentPath(null);
                pathManager.setNextPlannedPath(null);
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    @Override
    public void forceCancel() { // exposed on public api because :sob:
        cancelEverything();
        secretInternalSegmentCancel();
        synchronized (pathManager.getPathCalculationLock()) {
            pathManager.setActivePathCalculation(null);
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return calculationContext;
    }

    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (pathManager.getDestination() == null || currentPos == null || initialPosition == null) {
            return Optional.empty();
        }
        if (pathManager.getDestination().isInGoal(ctx.playerFeet())) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (elapsedTicks == 0) {
            return Optional.empty();
        }
        double current = pathManager.getDestination().heuristic(currentPos.x, currentPos.y, currentPos.z);
        double start = pathManager.getDestination().heuristic(initialPosition.x, initialPosition.y, initialPosition.z);
        if (current == start) {
            return Optional.empty();
        }
        double eta = Math.abs(current - pathManager.getDestination().heuristic()) * elapsedTicks / Math.abs(start - current);
        return Optional.of(eta);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(pathManager.getExpectedPathStart());
    }

    public void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        elapsedTicks = 0;
        initialPosition = start;
    }

    public BetterBlockPos pathStart() {
        return pathHelper.pathStart();
    }

    private void findNewPathThreaded(final BlockPos start, final boolean log, CalculationContext context) {
        synchronized (pathManager.getPathCalculationLock()) {
            pathManager.startNewPathCalculation(start, log, context);
        }
    }

    public static AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous, CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos) {
            BlockPos pos = ((IGoalRenderPos) goal).getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }
        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        return new AStarPathFinder(start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
    }
}
