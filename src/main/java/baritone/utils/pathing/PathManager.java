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

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.PathExecutor;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;

import static baritone.behavior.PathingBehavior.createPathfinder;

/**
 * Manages pathfinding calculations in separate threads
 */
public class PathManager implements Helper  {
    private final PathingBehavior pathingBehavior;
    private final Object pathCalculationLock = new Object();
    private final Object pathLock = new Object();

    private AbstractNodeCostSearch activePathCalculation;

    private PathExecutor currentPath;
    private PathExecutor nextPlannedPath;
    private BetterBlockPos expectedPathStart;
    private Goal goal;

    public PathManager(PathingBehavior pathingBehavior) {
        this.pathingBehavior = pathingBehavior;
    }

    /**
     * Initiates pathfinding to target position in a new thread
     *
     * @param start Starting position
     * @param log Whether to log debug information
     * @param context Calculation context for pathfinding
     * @throws IllegalStateException if called without proper synchronization or invalid state
     */
    public void startNewPathCalculation(final BlockPos start, final boolean log, CalculationContext context) {
        validateCalculationRequest(context);

        Goal goal = this.goal;
        if (goal == null) {
            logDebug("No goal");
            return;
        }

        this.expectedPathStart = BetterBlockPos.from(start);

        TimeoutPair timeouts = determineTimeouts();
        AbstractNodeCostSearch pathfinder = initializePathfinder(start, goal, context);

        executePathfinding(start, goal, pathfinder, log, timeouts);
    }

    private void validateCalculationRequest(CalculationContext context) {
        if (!Thread.holdsLock(pathCalculationLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalculationLock");
        }
        if (activePathCalculation != null) {
            throw new IllegalStateException("Already doing it");
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }
    }

    private TimeoutPair determineTimeouts() {
        if (currentPath == null) {
            return new TimeoutPair(
                    Baritone.settings().primaryTimeoutMS.value,
                    Baritone.settings().failureTimeoutMS.value
            );
        }
        return new TimeoutPair(
                Baritone.settings().planAheadPrimaryTimeoutMS.value,
                Baritone.settings().planAheadFailureTimeoutMS.value
        );
    }

    private AbstractNodeCostSearch initializePathfinder(BlockPos start, Goal goal, CalculationContext context) {
        AbstractNodeCostSearch pathfinder = createPathfinder(
                start,
                goal,
                currentPath == null ? null : currentPath.getPath(),
                context
        );

        if (!Objects.equals(pathfinder.getGoal(), goal)) {
            logDebug("Simplifying " + goal.getClass() + " to GoalXZ due to distance");
        }

        activePathCalculation = pathfinder;
        return pathfinder;
    }

    private void executePathfinding(BlockPos start, Goal goal, AbstractNodeCostSearch pathfinder,
                                    boolean log, TimeoutPair timeouts) {
        Baritone.getExecutor().execute(() -> {
            if (log) {
                logDebug("Starting to search for path from " + start + " to " + goal);
            }

            PathCalculationResult calcResult = pathfinder.calculate(
                    timeouts.primaryTimeout,
                    timeouts.failureTimeout
            );

            handleCalculationResult(calcResult, start, goal, log);
        });
    }

    private void handleCalculationResult(PathCalculationResult calcResult, BlockPos start,
                                         Goal goal, boolean log) {
        synchronized (pathLock) {
            Optional<PathExecutor> executor = calcResult.getPath()
                    .map(p -> new PathExecutor(pathingBehavior, p));

            if (currentPath == null) {
                handleInitialPathResult(executor, calcResult, start);
            } else {
                handleNextSegmentResult(executor);
            }

            logCalculationOutcome(log, goal, start);

            synchronized (pathCalculationLock) {
                activePathCalculation = null;
            }
        }
    }

    private void handleInitialPathResult(Optional<PathExecutor> executor,
                                         PathCalculationResult calcResult, BlockPos start) {
        if (executor.isPresent()) {
            BetterBlockPos pathStart = executor.get().getPath().positions().getFirst();
            if (pathStart.equals(expectedPathStart)) {
                pathingBehavior.queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                currentPath = executor.get();
                pathingBehavior.resetEstimatedTicksToGoal(BetterBlockPos.from(start));
            } else {
                logDebug("Warning: discarding orphan path segment with incorrect start. Expected: "
                        + expectedPathStart + ", Got: " + pathStart);
            }
        } else if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION
                && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
            pathingBehavior.queuePathEvent(PathEvent.CALC_FAILED);
        }
    }

    private void handleNextSegmentResult(Optional<PathExecutor> executor) {
        if (nextPlannedPath == null) {
            if (executor.isPresent()) {
                if (executor.get().getPath().getSrc().equals(currentPath.getPath().getDest())) {
                    pathingBehavior.queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                    nextPlannedPath = executor.get();
                } else {
                    logDebug("Warning: discarding orphan next segment with incorrect start");
                }
            } else {
                pathingBehavior.queuePathEvent(PathEvent.NEXT_CALC_FAILED);
            }
        } else {
            pathingBehavior.logDirect("Warning: PathingBehaivor illegal state! Discarding invalid path!");
        }
    }

    public void handlePauseResume(BlockPos newPosition) {
        synchronized (pathLock) {
            // Clear the current path since we're in a new position
            if (currentPath != null) {
                currentPath = null;
            }

            // Update the expected start position for the new calculation
            expectedPathStart = BetterBlockPos.from(newPosition);

            // Clear any planned paths since they're no longer valid
            if (nextPlannedPath != null) {
                nextPlannedPath = null;
            }

            // Cancel any active calculations as they're using the old position
            synchronized (pathCalculationLock) {
                if (activePathCalculation != null) {
                    activePathCalculation.cancel();
                    activePathCalculation = null;
                }
            }
        }
    }

    private void logCalculationOutcome(boolean log, Goal goal, BlockPos start) {
        if (log && currentPath != null && currentPath.getPath() != null) {
            String message = goal.isInGoal(currentPath.getPath().getDest())
                    ? "Finished finding a path"
                    : "Found path segment";

            logDebug(String.format("%s from %s to %s. %d nodes considered",
                    message, start, goal, currentPath.getPath().getNumNodesConsidered()));
        }
    }

    private record TimeoutPair(long primaryTimeout, long failureTimeout) {
    }

    public PathExecutor getCurrentPath() {
        return currentPath;
    }

    public PathExecutor getNextPlannedPath() {
        return nextPlannedPath;
    }

    public PathingBehavior getPathingBehavior() {
        return pathingBehavior;
    }

    public Object getPathCalculationLock() {
        return pathCalculationLock;
    }

    public Object getPathLock() {
        return pathLock;
    }

    public AbstractNodeCostSearch getActivePathCalculation() {
        return activePathCalculation;
    }

    public BlockPos getExpectedPathStart() {
        return expectedPathStart;
    }

    public Goal getGoal() {
        return goal;
    }

    public void setGoal(Goal goal) {
        this.goal = goal;
    }

    public void setExpectedPathStart(BetterBlockPos start) {
        this.expectedPathStart = start;
    }

    public void setCurrentPath(PathExecutor currentPath) {
        this.currentPath = currentPath;
    }

    public void setNextPlannedPath(PathExecutor nextPlannedPath) {
        this.nextPlannedPath = nextPlannedPath;
    }

    public void setActivePathCalculation(AbstractNodeCostSearch activePathCalculation) {
        this.activePathCalculation = activePathCalculation;
    }
}
