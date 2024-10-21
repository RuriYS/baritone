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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Determines the starting position for a new path, accounting for various player positions
 * and ground conditions.
 *
 * @return The starting {@link BlockPos} for a new path
 */
public class PathStartHelper {
    private final IPlayerContext ctx;
    private static final int SEARCH_RADIUS = 1;
    private static final double MAX_EDGE_DISTANCE = 0.8;
    private static final int CLOSEST_POSITIONS_TO_CHECK = 4;

    public PathStartHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public BetterBlockPos pathStart() {
        BetterBlockPos playerFeet = ctx.playerFeet();

        if (MovementHelper.canWalkOn(ctx, playerFeet.below())) {
            return playerFeet;
        }

        return ctx.player().onGround()
                ? findValidGroundPosition(playerFeet)
                : handleMidairPosition(playerFeet);
    }

    private BetterBlockPos handleMidairPosition(BetterBlockPos playerFeet) {
        if (MovementHelper.canWalkOn(ctx, playerFeet.below().below())) {
            return playerFeet.below();
        }
        return playerFeet;
    }

    private BetterBlockPos findValidGroundPosition(BetterBlockPos playerFeet) {
        Vec3 playerPos = ctx.player().position();
        List<BetterBlockPos> nearbyPositions = getNearbyPositionsSortedByDistance(
                playerFeet,
                playerPos.x,
                playerPos.z
        );

        for (int i = 0; i < Math.min(CLOSEST_POSITIONS_TO_CHECK, nearbyPositions.size()); i++) {
            BetterBlockPos candidate = nearbyPositions.get(i);
            if (isValidStandingPosition(candidate, playerPos)) {
                return candidate;
            }
        }

        return playerFeet;
    }

    private List<BetterBlockPos> getNearbyPositionsSortedByDistance(
            BetterBlockPos center,
            double playerX,
            double playerZ
    ) {
        List<BetterBlockPos> positions = new ArrayList<>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                positions.add(new BetterBlockPos(
                        center.x + dx,
                        center.y,
                        center.z + dz
                ));
            }
        }

        positions.sort(Comparator.comparingDouble(pos ->
                getSquaredDistance(pos, playerX, playerZ)
        ));

        return positions;
    }

    private double getSquaredDistance(BetterBlockPos pos, double playerX, double playerZ) {
        double dx = (pos.x + 0.5D) - playerX;
        double dz = (pos.z + 0.5D) - playerZ;
        return dx * dx + dz * dz;
    }

    private boolean isValidStandingPosition(BetterBlockPos pos, Vec3 playerPos) {
        if (!isWithinSneakingRange(pos, playerPos)) {
            return false;
        }

        return canStandAt(pos);
    }

    private boolean isWithinSneakingRange(BetterBlockPos pos, Vec3 playerPos) {
        double xDist = Math.abs((pos.x + 0.5D) - playerPos.x);
        double zDist = Math.abs((pos.z + 0.5D) - playerPos.z);
        return xDist <= MAX_EDGE_DISTANCE || zDist <= MAX_EDGE_DISTANCE;
    }

    private boolean canStandAt(BetterBlockPos pos) {
        return MovementHelper.canWalkOn(ctx, pos.below()) &&
                MovementHelper.canWalkThrough(ctx, pos) &&
                MovementHelper.canWalkThrough(ctx, pos.above());
    }
}