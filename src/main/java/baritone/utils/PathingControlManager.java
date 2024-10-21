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

package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import net.minecraft.core.BlockPos;

import java.util.*;

public class PathingControlManager implements IPathingControlManager {

    private final Baritone baritone;
    private final HashSet<IBaritoneProcess> registeredProcesses;
    private final List<IBaritoneProcess> activeProcesses;
    private IBaritoneProcess previousControllingProcess;
    private IBaritoneProcess currentControllingProcess;
    private PathingCommand currentCommand;

    public PathingControlManager(Baritone baritone) {
        this.baritone = baritone;
        this.registeredProcesses = new HashSet<>();
        this.activeProcesses = new ArrayList<>();

        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    handlePostTickProcessing();
                }
            }
        });
    }

    @Override
    public void registerProcess(IBaritoneProcess process) {
        process.release(); // Reset the process state
        registeredProcesses.add(process);
    }

    public void terminateAllProcesses() {
        previousControllingProcess = null;
        currentControllingProcess = null;
        currentCommand = null;
        activeProcesses.clear();

        for (IBaritoneProcess process : registeredProcesses) {
            process.release();
            if (process.isActive() && !process.isTemporary()) {
                throw new IllegalStateException(process.displayName());
            }
        }
    }

    @Override
    public Optional<IBaritoneProcess> mostRecentInControl() {
        return Optional.ofNullable(currentControllingProcess);
    }

    @Override
    public Optional<PathingCommand> mostRecentCommand() {
        return Optional.ofNullable(currentCommand);
    }

    public void handlePreTickProcessing() {
        previousControllingProcess = currentControllingProcess;
        currentControllingProcess = null;
        PathingBehavior pathingBehavior = baritone.getPathingBehavior();

        currentCommand = executeProcessQueue();
        if (currentCommand == null) {
            pathingBehavior.cancelSegmentIfSafe();
            pathingBehavior.secretInternalSetGoal(null);
            return;
        }

        // Handle process control changes
        if (!Objects.equals(currentControllingProcess, previousControllingProcess) &&
                currentCommand.commandType != PathingCommandType.REQUEST_PAUSE &&
                previousControllingProcess != null &&
                !previousControllingProcess.isTemporary()) {
            pathingBehavior.cancelSegmentIfSafe();
        }

        executePathingCommand(pathingBehavior, currentCommand);
    }

    private void executePathingCommand(PathingBehavior pathingBehavior, PathingCommand command) {
        switch (command.commandType) {
            case SET_GOAL_AND_PAUSE:
                pathingBehavior.secretInternalSetGoalAndPath(command);
                pathingBehavior.requestPause();
                break;
            case REQUEST_PAUSE:
                pathingBehavior.requestPause();
                break;
            case CANCEL_AND_SET_GOAL:
                pathingBehavior.secretInternalSetGoal(command.goal);
                pathingBehavior.cancelSegmentIfSafe();
                break;
            case FORCE_REVALIDATE_GOAL_AND_PATH:
            case REVALIDATE_GOAL_AND_PATH:
                if (!pathingBehavior.isPathing() && !pathingBehavior.getActivePathCalculation().isPresent()) {
                    pathingBehavior.secretInternalSetGoalAndPath(command);
                }
                break;
            case SET_GOAL_AND_PATH:
                if (command.goal != null) {
                    pathingBehavior.secretInternalSetGoalAndPath(command);
                }
                break;
            default:
                throw new IllegalStateException("Unknown command type: " + command.commandType);
        }
    }

    private void handlePostTickProcessing() {
        if (currentCommand == null) {
            return;
        }

        PathingBehavior pathingBehavior = baritone.getPathingBehavior();

        if (currentCommand.commandType == PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH) {
            handleForceRevalidation(pathingBehavior);
        } else if (currentCommand.commandType == PathingCommandType.REVALIDATE_GOAL_AND_PATH) {
            handleRevalidation(pathingBehavior);
        }
    }

    private void handleForceRevalidation(PathingBehavior pathingBehavior) {
        if (currentCommand.goal == null || requiresForceRevalidation(currentCommand.goal) || requiresGoalRevalidation(currentCommand.goal)) {
            pathingBehavior.softCancelIfSafe();
        }
        pathingBehavior.secretInternalSetGoalAndPath(currentCommand);
    }

    private void handleRevalidation(PathingBehavior pathingBehavior) {
        if (Baritone.settings().cancelOnGoalInvalidation.value &&
                (currentCommand.goal == null || requiresGoalRevalidation(currentCommand.goal))) {
            pathingBehavior.softCancelIfSafe();
        }
        pathingBehavior.secretInternalSetGoalAndPath(currentCommand);
    }

    public boolean requiresForceRevalidation(Goal newGoal) {
        PathExecutor currentPath = baritone.getPathingBehavior().getCurrentPath();
        if (currentPath != null) {
            if (newGoal.isInGoal(currentPath.getPath().getDest())) {
                return false;
            }
            return !newGoal.equals(currentPath.getPath().getGoal());
        }
        return false;
    }

    public boolean requiresGoalRevalidation(Goal newGoal) {
        PathExecutor currentPath = baritone.getPathingBehavior().getCurrentPath();
        if (currentPath != null) {
            Goal intendedGoal = currentPath.getPath().getGoal();
            BlockPos endPosition = currentPath.getPath().getDest();
            return intendedGoal.isInGoal(endPosition) && !newGoal.isInGoal(endPosition);
        }
        return false;
    }

    public PathingCommand executeProcessQueue() {
        updateActiveProcessList();
        sortProcessesByPriority();

        for (Iterator<IBaritoneProcess> iterator = activeProcesses.iterator(); iterator.hasNext();) {
            IBaritoneProcess process = iterator.next();
            boolean wasInControlLastTick = Objects.equals(process, previousControllingProcess);
            boolean pathCalculationFailedLastTick = baritone.getPathingBehavior().calcFailedLastTick();
            boolean isSafeToCancelCurrentPath = baritone.getPathingBehavior().isSafeToCancel();

            PathingCommand command = process.onTick(wasInControlLastTick && pathCalculationFailedLastTick, isSafeToCancelCurrentPath);

            if (command == null) {
                if (process.isActive()) {
                    throw new IllegalStateException(process.displayName() + " actively returned null PathingCommand");
                }
            } else if (command.commandType != PathingCommandType.DEFER) {
                currentControllingProcess = process;
                if (!process.isTemporary()) {
                    iterator.forEachRemaining(IBaritoneProcess::release);
                }
                return command;
            }
        }
        return null;
    }

    private void updateActiveProcessList() {
        for (IBaritoneProcess process : registeredProcesses) {
            if (process.isActive()) {
                if (!activeProcesses.contains(process)) {
                    activeProcesses.add(0, process);
                }
            } else {
                activeProcesses.remove(process);
            }
        }
    }

    private void sortProcessesByPriority() {
        activeProcesses.sort(Comparator.comparingDouble(IBaritoneProcess::priority).reversed());
    }
}