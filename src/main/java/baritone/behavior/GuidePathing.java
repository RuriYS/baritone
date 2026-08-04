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
import baritone.api.event.events.PathEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.Tuple;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.path.CutoffPath;
import baritone.pathing.path.PathExecutor;

import java.util.Objects;
import java.util.Optional;

final class GuidePathing {

    private final PathingBehavior behavior;
    private final Object lock = new Object();

    private volatile boolean active;
    private boolean recovering;
    private boolean recoveryCalculating;
    private boolean extensionFailed;
    private int divergedTicks;
    private int validationTicks;
    private int generation;

    private Goal goal;
    private CalculationContext context;
    private PathExecutor current;
    private PathExecutor next;
    private volatile AbstractNodeCostSearch inProgress;

    GuidePathing(PathingBehavior behavior) {
        this.behavior = behavior;
    }

    boolean start(Goal goal, CalculationContext context) {
        synchronized (lock) {
            if (!active || !Objects.equals(this.goal, goal)) {
                cancelCalculation();
                active = true;
                generation++;
                recovering = false;
                recoveryCalculating = false;
                extensionFailed = false;
                divergedTicks = 0;
                validationTicks = 0;
                current = null;
                next = null;
                this.goal = goal;
            }
            this.context = context;
            if (goal == null || goal.isInGoal(behavior.ctx.playerFeet())) {
                return false;
            }
            if (current == null && inProgress == null) {
                calculateBackbone(false);
                return true;
            }
            return false;
        }
    }

    void tick() {
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        synchronized (lock) {
            if (current == null) {
                return;
            }
            double maxDistance = Math.max(0.0D, Baritone.settings().guideRepathDistance.value);
            int delay = Math.max(1, Baritone.settings().guideRepathDelayTicks.value);
            int fragmentLength = Math.max(1, Baritone.settings().guideFragmentLength.value);

            if (next != null) {
                Tuple<Double, Integer> status = next.guideTick(maxDistance);
                if (status.getA() <= maxDistance) {
                    current = next;
                    next = null;
                    recovering = false;
                    divergedTicks = 0;
                    validationTicks = 0;
                    return;
                }
            }

            Tuple<Double, Integer> status = current.guideTick(maxDistance);
            if (recoveryCalculating && status.getA() <= maxDistance) {
                generation++;
                recoveryCalculating = false;
                inProgress.cancel();
                divergedTicks = 0;
                return;
            }
            divergedTicks = status.getA() <= maxDistance ? 0 : divergedTicks + 1;

            if (++validationTicks >= delay) {
                validationTicks = 0;
                if (!current.guidePathValid(context, fragmentLength)) {
                    recalculate();
                    return;
                }
            }

            if (recovering) {
                if (divergedTicks >= delay && inProgress == null) {
                    recalculate();
                }
                return;
            }
            if (extensionFailed) {
                recalculate();
                return;
            }
            if (divergedTicks >= delay) {
                if (inProgress == null) {
                    calculateRecovery(fragmentLength);
                }
                return;
            }

            int remaining = current.getPath().length() - 1 - current.getPosition();
            if (!goal.isInGoal(current.getPath().getDest())
                    && remaining <= fragmentLength
                    && next == null
                    && inProgress == null) {
                calculateBackbone(true);
            }
        }
    }

    void stop() {
        synchronized (lock) {
            active = false;
            generation++;
            recoveryCalculating = false;
            cancelCalculation();
            current = null;
            next = null;
            goal = null;
            context = null;
        }
    }

    boolean isActive() {
        return active;
    }

    PathExecutor getCurrent() {
        return current;
    }

    PathExecutor getNext() {
        return next;
    }

    Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    private void calculateBackbone(boolean extension) {
        BetterBlockPos start = extension ? current.getPath().getDest() : behavior.pathStart();
        IPath previous = extension ? current.getPath() : null;
        long primaryTimeout = extension
                ? Baritone.settings().planAheadPrimaryTimeoutMS.value
                : Baritone.settings().primaryTimeoutMS.value;
        long failureTimeout = extension
                ? Baritone.settings().planAheadFailureTimeoutMS.value
                : Baritone.settings().failureTimeoutMS.value;
        AbstractNodeCostSearch pathfinder = behavior.createPathfinder(start, goal, previous, context);
        int calculationGeneration = generation;
        inProgress = pathfinder;
        extensionFailed = false;
        behavior.queuePathEvent(extension ? PathEvent.NEXT_SEGMENT_CALC_STARTED : PathEvent.CALC_STARTED);

        Baritone.getExecutor().execute(() -> {
            PathCalculationResult result = pathfinder.calculate(primaryTimeout, failureTimeout);
            synchronized (lock) {
                if (inProgress != pathfinder) {
                    return;
                }
                inProgress = null;
                if (!active || generation != calculationGeneration) {
                    return;
                }
                Optional<PathExecutor> executor = result.getPath().map(path -> new PathExecutor(behavior, path));
                if (extension) {
                    if (executor.isPresent() && current != null
                            && executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                        next = executor.get();
                        behavior.queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                    } else {
                        extensionFailed = true;
                        behavior.queuePathEvent(PathEvent.NEXT_CALC_FAILED);
                    }
                } else if (executor.isPresent()) {
                    current = executor.get();
                    behavior.queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                } else if (result.getType() != PathCalculationResult.Type.CANCELLATION
                        && result.getType() != PathCalculationResult.Type.EXCEPTION) {
                    behavior.queuePathEvent(PathEvent.CALC_FAILED);
                }
            }
        });
    }

    private void calculateRecovery(int fragmentLength) {
        int currentPosition = current.getPosition();
        long lookahead = (long) fragmentLength * 2L;
        IPath backbone = current.getPath();
        long currentRemaining = backbone.length() - 1L - currentPosition;
        int anchorIndex = (int) Math.min((long) currentPosition + lookahead, backbone.length() - 1L);
        int firstUnvisited = currentPosition + 1;
        if (next != null && lookahead > currentRemaining) {
            int nextAnchor = (int) Math.min(lookahead - currentRemaining, next.getPath().length() - 1L);
            if (nextAnchor > 0) {
                backbone = next.getPath();
                anchorIndex = nextAnchor;
                firstUnvisited = 1;
            }
        }
        if (anchorIndex < firstUnvisited) {
            recalculate();
            return;
        }

        BetterBlockPos anchor = backbone.positions().get(anchorIndex);
        PathExecutor suffix = new PathExecutor(behavior, new CutoffPath(backbone, anchorIndex, backbone.length() - 1));
        AbstractNodeCostSearch pathfinder = behavior.createPathfinder(
                behavior.pathStart(), new GoalBlock(anchor), backbone, context, false
        );
        int calculationGeneration = generation;
        inProgress = pathfinder;
        recoveryCalculating = true;
        behavior.queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);

        Baritone.getExecutor().execute(() -> {
            PathCalculationResult result = pathfinder.calculate(
                    Baritone.settings().primaryTimeoutMS.value,
                    Baritone.settings().failureTimeoutMS.value
            );
            synchronized (lock) {
                if (inProgress != pathfinder) {
                    return;
                }
                inProgress = null;
                recoveryCalculating = false;
                if (!active || generation != calculationGeneration) {
                    return;
                }
                Optional<IPath> connector = result.getPath();
                if (connector.isPresent() && connector.get().getDest().equals(anchor)) {
                    current = new PathExecutor(behavior, connector.get());
                    next = suffix;
                    recovering = true;
                    divergedTicks = 0;
                    validationTicks = 0;
                    behavior.queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                } else {
                    recalculate();
                }
            }
        });
    }

    private void recalculate() {
        generation++;
        recovering = false;
        recoveryCalculating = false;
        extensionFailed = false;
        divergedTicks = 0;
        validationTicks = 0;
        current = null;
        next = null;
        if (inProgress != null) {
            inProgress.cancel();
        } else if (goal != null && !goal.isInGoal(behavior.ctx.playerFeet())) {
            calculateBackbone(false);
        }
    }

    private void cancelCalculation() {
        if (inProgress != null) {
            inProgress.cancel();
        }
    }
}
