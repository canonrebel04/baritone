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

package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.ElytraBehavior;
import baritone.process.elytra.NetherPathfinderContext;
import baritone.process.elytra.NullElytraProcess;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private boolean predictingTerrain;

    private boolean metricsAttemptActive;
    private long metricsAttemptStartNanos;
    private int metricsAttemptSeq;
    private BlockPos metricsAttemptDestination;
    private String metricsAttemptStartDimension;

    private Vec3 metricsAttemptStartPos;
    private double metricsAttemptStartDistSq;
    private double metricsAttemptStartDistSqXZ;
    private double metricsAttemptMinDistSq;
    private double metricsAttemptMinDistSqXZ;
    private int metricsAttemptMinDistTick;
    private int metricsAttemptTicks;
    private int metricsAttemptGlideTicks;
    private double metricsAttemptSpeedSum;
    private double metricsAttemptSpeedMax;
    private double metricsAttemptSpeedSumXZ;
    private double metricsAttemptSpeedMaxXZ;

    private String metricsAttemptLostControlTo;
    private String metricsAttemptLostControlToClass;
    private String metricsAttemptLostControlSource;

    private boolean manualWalkOffJump;
    private BetterBlockPos manualJumpStandPos;
    private BetterBlockPos manualJumpOffPos;

    private record JumpOffSpot(BetterBlockPos stand, BetterBlockPos off) {
    }

    private JumpOffSpot findOverworldJumpOffSpot(int radius, int minDropBlocks) {
        if (ctx.player() == null || ctx.world() == null) {
            return null;
        }
        final Level world = ctx.world();
        final BlockPos playerBlockPos = ctx.player().blockPosition();
        final int baseX = playerBlockPos.getX();
        final int baseZ = playerBlockPos.getZ();

        JumpOffSpot best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) {
                    continue;
                }

                final int x = baseX + dx;
                final int z = baseZ + dz;

                final int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                final BlockPos feet = new BlockPos(x, y, z);

                if (!world.getBlockState(feet).isAir() || !world.getBlockState(feet.above()).isAir()) {
                    continue;
                }

                final BlockState below = world.getBlockState(feet.below());
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    continue;
                }

                // Overworld guardrail: avoid choosing subterranean "jump spots" (caves) when auto-jumping.
                if (!world.canSeeSky(feet)) {
                    continue;
                }

                for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    final BlockPos off = feet.relative(dir);
                    if (!world.getBlockState(off).isAir() || !world.getBlockState(off.above()).isAir()) {
                        continue;
                    }

                    boolean hasDrop = true;
                    for (int i = 1; i <= minDropBlocks; i++) {
                        if (!world.getBlockState(off.below(i)).isAir()) {
                            hasDrop = false;
                            break;
                        }
                    }
                    if (!hasDrop) {
                        continue;
                    }

                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = new JumpOffSpot(new BetterBlockPos(feet), new BetterBlockPos(off));
                    }
                }
            }
        }

        return best;
    }

    private void metricsLandingSpotSelection(BetterBlockPos last, boolean pathComplete, boolean safetyLanding, BetterBlockPos chosenLandingSpot, String searchOrigin) {
        if (!this.metricsAttemptActive) {
            return;
        }
        if (!this.baritone.getMetricsRecorder().isRunning()) {
            return;
        }
        try {
            final int id = this.metricsAttemptSeq;
            final BlockPos destination = this.metricsAttemptDestination;
            final Vec3 playerPos = ctx.player() != null ? ctx.player().position() : null;
            final Vec3 destPos = destination != null ? center(destination) : null;

            final double playerDistXZ = (playerPos != null && destPos != null) ? Math.sqrt(distSqXZ(playerPos, destPos)) : Double.NaN;
            final double lastToDestXZ = (last != null && destPos != null) ? Math.sqrt(distSqXZ(center(new BlockPos(last.x, last.y, last.z)), destPos)) : Double.NaN;
            final double chosenToDestXZ = (chosenLandingSpot != null && destPos != null)
                    ? Math.sqrt(distSqXZ(center(new BlockPos(chosenLandingSpot.x, chosenLandingSpot.y, chosenLandingSpot.z)), destPos))
                    : Double.NaN;

            this.baritone.getMetricsRecorder().record("elytra_landing_select", obj -> {
                obj.addProperty("id", id);

                if (ctx.player() != null) {
                    obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().toString()));
                    obj.addProperty("creative", ctx.player().getAbilities().instabuild);
                }

                obj.addProperty("path_complete", pathComplete);
                obj.addProperty("safety_landing", safetyLanding);
                if (searchOrigin != null) {
                    obj.addProperty("search_origin", searchOrigin);
                }

                if (destination != null) {
                    obj.addProperty("dest_x", destination.getX());
                    obj.addProperty("dest_y", destination.getY());
                    obj.addProperty("dest_z", destination.getZ());
                }
                if (playerPos != null) {
                    obj.addProperty("player_x", playerPos.x);
                    obj.addProperty("player_y", playerPos.y);
                    obj.addProperty("player_z", playerPos.z);
                }
                if (!Double.isNaN(playerDistXZ)) {
                    obj.addProperty("player_dist_xz", playerDistXZ);
                }

                if (last != null) {
                    obj.addProperty("last_x", last.x);
                    obj.addProperty("last_y", last.y);
                    obj.addProperty("last_z", last.z);
                }
                if (!Double.isNaN(lastToDestXZ)) {
                    obj.addProperty("last_to_dest_dist_xz", lastToDestXZ);
                }

                obj.addProperty("landing_found", chosenLandingSpot != null);
                if (chosenLandingSpot != null) {
                    obj.addProperty("landing_x", chosenLandingSpot.x);
                    obj.addProperty("landing_y", chosenLandingSpot.y);
                    obj.addProperty("landing_z", chosenLandingSpot.z);
                }
                if (!Double.isNaN(chosenToDestXZ)) {
                    obj.addProperty("landing_to_dest_dist_xz", chosenToDestXZ);
                }
            });
        } catch (Throwable ignored) {
            // must never crash gameplay
        }
    }

    private void cancelAndCleanup() {
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        this.manualWalkOffJump = false;
        this.manualJumpStandPos = null;
        this.manualJumpOffPos = null;
        destroyBehaviorAsync();
    }

    @Override
    public void onLostControl() {
        if (this.metricsAttemptActive && this.metricsAttemptLostControlSource == null) {
            IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
            if (procThisTick != null && procThisTick != this) {
                this.metricsAttemptLostControlSource = "preempted";
                this.metricsAttemptLostControlTo = procThisTick.displayName();
                this.metricsAttemptLostControlToClass = procThisTick.getClass().getName();
                logDirect("Elytra cancelled: lost control to " + this.metricsAttemptLostControlTo);
            } else {
                // Best-effort attribution. This is intentionally conservative (must never crash gameplay).
                try {
                    if (ctx.player() == null) {
                        this.metricsAttemptLostControlSource = "noPlayer";
                    } else if (ctx.world() == null) {
                        this.metricsAttemptLostControlSource = "noWorld";
                    } else if (ctx.player().isDeadOrDying()) {
                        this.metricsAttemptLostControlSource = "dead";
                    } else if (this.metricsAttemptStartDimension != null
                        && !this.metricsAttemptStartDimension.equals(String.valueOf(ctx.player().level().dimension().toString()))) {
                        this.metricsAttemptLostControlSource = "leftDimension";
                    } else if (this.metricsAttemptGlideTicks > 0 && !ctx.player().isFallFlying()) {
                        // We were gliding for at least one tick, then stopped.
                        this.metricsAttemptLostControlSource = "stoppedGliding";
                    } else if (this.state == State.FLYING && !ctx.player().isFallFlying()) {
                        this.metricsAttemptLostControlSource = "stoppedGliding";
                    } else {
                        this.metricsAttemptLostControlSource = "unknown";
                    }
                } catch (Throwable ignored) {
                    this.metricsAttemptLostControlSource = "unknown";
                }
            }
        }
        metricsFinish(false, "lostControl");
        cancelAndCleanup();
    }

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final Baritone baritone) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        if (destination != null) {
            this.pathTo(destination);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        // Seed only matters when predicting terrain (and is nether-specific).
        if (this.predictingTerrain) {
            final long seedSetting = Baritone.settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.context.getSeed()) {
                logDirect("Nether seed changed, recalculating path");
                this.resetState();
            }
        }
        if (predictingTerrain != Baritone.settings().elytraPredictTerrain.value) {
            logDirect("elytraPredictTerrain setting changed, recalculating path");
            predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
            this.resetState();
        }

        this.behavior.onTick();

        if (calcFailed) {
            metricsFinish(false, "calcFailed");
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean safetyLanding = false;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (Baritone.settings().elytraAllowEmergencyLand.value) {
                logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final boolean pathComplete = this.behavior.pathManager.isComplete();
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                final BlockPos primaryDestination = this.metricsAttemptDestination != null ? this.metricsAttemptDestination : this.currentDestination();

                // If this was triggered as a normal "path complete" (not an emergency landing), avoid selecting a landing
                // destination too early. We want to keep gliding/orbiting until we're actually close to the goal.
                boolean allowLandingSpotSelection = true;
                if (!safetyLanding && primaryDestination != null) {
                    Vec3 destPos = center(primaryDestination);
                    double playerDistXZ = Math.sqrt(distSqXZ(ctx.player().position(), destPos));
                    if (!Double.isNaN(playerDistXZ) && playerDistXZ > 24.0) {
                        allowLandingSpotSelection = false;
                    }
                }

                if (allowLandingSpotSelection) {
                    logDirect("Path complete, picking a nearby safe landing spot...");

                    String searchOrigin = "player";
                    BetterBlockPos landingSpot = null;

                // Prefer landing spots near the last path node first. The last node is already close to the goal and
                // tends to be much more reliable than searching near the player.
                if (!safetyLanding) {
                    BetterBlockPos lastStart = last.above(LANDING_COLUMN_HEIGHT);
                    landingSpot = findSafeLandingSpot(lastStart);
                    if (landingSpot != null) {
                        searchOrigin = "last";
                    }
                }

                // Secondary: search near the intended destination.
                if (landingSpot == null && !safetyLanding && primaryDestination != null) {
                    BetterBlockPos destStart = new BetterBlockPos(primaryDestination).above(LANDING_COLUMN_HEIGHT);
                    landingSpot = findSafeLandingSpot(destStart);
                    if (landingSpot != null) {
                        searchOrigin = "dest";
                    }
                }
                if (landingSpot == null) {
                    landingSpot = findSafeLandingSpot(ctx.playerFeet());
                    if (landingSpot != null) {
                        searchOrigin = "player";
                    }
                }

                    // Guardrail: never intentionally land *much farther* from the destination unless it's an emergency.
                    if (!safetyLanding && landingSpot != null && primaryDestination != null) {
                        Vec3 destPos = center(primaryDestination);
                        Vec3 playerPos = ctx.player().position();
                        double playerDistXZ = Math.sqrt(distSqXZ(playerPos, destPos));
                        double landingDistXZ = Math.sqrt(distSqXZ(center(new BlockPos(landingSpot.x, landingSpot.y, landingSpot.z)), destPos));
                        if (!Double.isNaN(playerDistXZ) && !Double.isNaN(landingDistXZ) && landingDistXZ > playerDistXZ + 8.0) {
                            landingSpot = null;
                        }
                    }

                    metricsLandingSpotSelection(last, pathComplete, safetyLanding, landingSpot, searchOrigin);
                    // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                    if (landingSpot != null) {
                        this.pathTo0(landingSpot, true);
                        this.landingSpot = landingSpot;
                    }
                    this.goingToLandingSpot = true;
                }
            }

            if (last != null && ctx.player().position().distanceToSqr(last.getCenter()) < 1) {
                if (Baritone.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (Baritone.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        metricsFinish(true, "disconnectOnArrival");
                        ctx.minecraft().getConnection().getConnection().disconnect(Component.literal("Disconnecting"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - LANDING_COLUMN_HEIGHT) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            // We are actively gliding; reflect that in the state machine.
            if (this.state == State.START_FLYING) {
                this.state = State.FLYING;
            }
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            behavior.tick();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            baritone.getInputOverrideHandler().clearAllKeys();
            metricsFinish(true, "landed");
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && Baritone.settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                metricsFinish(false, "safetyNoTakeoff");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            // Overworld: do NOT use the legacy "GoalYLevel(31)" jump search.
            // It tends to pick caves/holes because it only cares about finding a fall movement.
            if (ctx.player() != null && ctx.player().level().dimension() != Level.NETHER) {
                if (!this.manualWalkOffJump || this.goal == null || !(this.goal instanceof GoalBlock)) {
                    final JumpOffSpot spot = findOverworldJumpOffSpot(24, 3);
                    if (spot == null) {
                        logDirect("Couldn't find a safe jump-off ledge nearby (surface/open sky). Try moving to a cliff/ledge, or disable elytraAutoJump and start gliding manually.");
                        metricsFinish(false, "noJumpSpot");
                        onLostControl();
                        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    this.manualWalkOffJump = true;
                    this.manualJumpStandPos = spot.stand();
                    this.manualJumpOffPos = spot.off();
                    this.goal = new GoalBlock(spot.stand().x, spot.stand().y, spot.stand().z);
                }
                this.state = State.GET_TO_JUMP;
                return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PATH, new WalkOffCalculationContext(baritone));
            }

            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && (this.manualWalkOffJump || (executor != null && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall));

            if (this.manualWalkOffJump) {
                if (this.manualJumpStandPos != null && ctx.player().position().distanceToSqr(this.manualJumpStandPos.getCenter()) < 1.0 && ctx.player().onGround()) {
                    if (this.manualJumpOffPos != null) {
                        Vec3 from = ctx.player().position();
                        Vec3 to = new Vec3(((double) this.manualJumpOffPos.x) + 0.5, from.y, ((double) this.manualJumpOffPos.z) + 0.5);
                        Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                        baritone.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false);
                    }
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }

            if (canStartFlying) {
                this.state = State.START_FLYING;
                this.manualWalkOffJump = false;
                this.manualJumpStandPos = null;
                this.manualJumpOffPos = null;
            } else {
                if (this.manualWalkOffJump) {
                    return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PATH, new WalkOffCalculationContext(baritone));
                }
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            Baritone.getExecutor().execute(behavior::destroy);
        }
    }

    @Override
    public double priority() {
        return 0; // higher priority than CustomGoalProcess
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.behavior != null) {
            this.behavior.repackChunks();
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null) {
            return;
        }

        // Terrain prediction is nether-specific (seed/generation). Allow other dimensions only when using live chunk data.
        if (Baritone.settings().elytraPredictTerrain.value && ctx.player().level().dimension() != Level.NETHER) {
            logDirect("Elytra terrain prediction is nether-only. Set elytraPredictTerrain=false to use Elytra outside the nether.");
            if (this.metricsAttemptActive) {
                metricsFinish(false, "disallowedDimension");
            }
            cancelAndCleanup();
            return;
        }

        // We use appendDestination=true for internal reroutes (e.g. landing spot selection).
        // Those should not terminate the active telemetry attempt as a "lostControl" failure.
        if (appendDestination) {
            destroyBehaviorAsync();
        } else {
            if (this.metricsAttemptActive) {
                metricsFinish(false, "restart");
            }
            cancelAndCleanup();
        }
        this.predictingTerrain = Baritone.settings().elytraPredictTerrain.value;
        this.behavior = new ElytraBehavior(this.baritone, this, destination, appendDestination);
        if (ctx.world() != null) {
            this.behavior.repackChunks();
        }

        if (!appendDestination) {
            metricsStart(destination);
        }
        this.behavior.pathTo();
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static double distSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distSqXZ(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void metricsStart(BlockPos destination) {
        if (this.metricsAttemptActive) {
            return;
        }
        if (!this.baritone.getMetricsRecorder().isRunning()) {
            return;
        }
        this.metricsAttemptActive = true;
        this.metricsAttemptStartNanos = System.nanoTime();
        this.metricsAttemptDestination = destination;
        this.metricsAttemptStartDimension = ctx.player() != null ? String.valueOf(ctx.player().level().dimension().toString()) : null;
        final int id = ++this.metricsAttemptSeq;

        this.metricsAttemptStartPos = ctx.player() != null ? ctx.player().position() : null;
        this.metricsAttemptTicks = 0;
        this.metricsAttemptGlideTicks = 0;
        this.metricsAttemptSpeedSum = 0.0;
        this.metricsAttemptSpeedMax = 0.0;
        this.metricsAttemptSpeedSumXZ = 0.0;
        this.metricsAttemptSpeedMaxXZ = 0.0;
        this.metricsAttemptMinDistTick = -1;
        this.metricsAttemptStartDistSq = Double.NaN;
        this.metricsAttemptStartDistSqXZ = Double.NaN;
        this.metricsAttemptMinDistSq = Double.POSITIVE_INFINITY;
        this.metricsAttemptMinDistSqXZ = Double.POSITIVE_INFINITY;

        this.metricsAttemptLostControlTo = null;
        this.metricsAttemptLostControlToClass = null;
        this.metricsAttemptLostControlSource = null;

        if (this.metricsAttemptStartPos != null && destination != null) {
            Vec3 dest = center(destination);
            this.metricsAttemptStartDistSq = distSq(this.metricsAttemptStartPos, dest);
            this.metricsAttemptStartDistSqXZ = distSqXZ(this.metricsAttemptStartPos, dest);
            this.metricsAttemptMinDistSq = this.metricsAttemptStartDistSq;
            this.metricsAttemptMinDistSqXZ = this.metricsAttemptStartDistSqXZ;
            this.metricsAttemptMinDistTick = 0;
        }

        this.baritone.getMetricsRecorder().record("elytra_start", obj -> {
            obj.addProperty("id", id);

            if (ctx.player() != null) {
                obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().toString()));
                obj.addProperty("creative", ctx.player().getAbilities().instabuild);
            }

            if (destination != null) {
                obj.addProperty("dest_x", destination.getX());
                obj.addProperty("dest_y", destination.getY());
                obj.addProperty("dest_z", destination.getZ());
            }

            if (this.metricsAttemptStartPos != null) {
                obj.addProperty("start_x", this.metricsAttemptStartPos.x);
                obj.addProperty("start_y", this.metricsAttemptStartPos.y);
                obj.addProperty("start_z", this.metricsAttemptStartPos.z);
            }
            if (!Double.isNaN(this.metricsAttemptStartDistSq)) {
                obj.addProperty("start_dist", Math.sqrt(this.metricsAttemptStartDistSq));
            }
            if (!Double.isNaN(this.metricsAttemptStartDistSqXZ)) {
                obj.addProperty("start_dist_xz", Math.sqrt(this.metricsAttemptStartDistSqXZ));
            }

            obj.addProperty("seed", Baritone.settings().elytraNetherSeed.value);
            obj.addProperty("predictTerrain", Baritone.settings().elytraPredictTerrain.value);
            obj.addProperty("autoJump", Baritone.settings().elytraAutoJump.value);
        });
    }

    private void metricsFinish(boolean success, String reason) {
        if (!this.metricsAttemptActive) {
            return;
        }

        final int id = this.metricsAttemptSeq;
        final long elapsedMs = (System.nanoTime() - this.metricsAttemptStartNanos) / 1_000_000L;
        final BlockPos destination = this.metricsAttemptDestination;

        final Vec3 endPos = ctx.player() != null ? ctx.player().position() : null;
        final Vec3 endMotion = ctx.player() != null ? ctx.player().getDeltaMovement() : null;
        final int ticks = this.metricsAttemptTicks;
        final int glideTicks = this.metricsAttemptGlideTicks;

        final Vec3 startPos = this.metricsAttemptStartPos;
        final double startDistSq = this.metricsAttemptStartDistSq;
        final double startDistSqXZ = this.metricsAttemptStartDistSqXZ;
        final double minDistSq = this.metricsAttemptMinDistSq;
        final double minDistSqXZ = this.metricsAttemptMinDistSqXZ;
        final int minDistTick = this.metricsAttemptMinDistTick;
        final double speedMax = this.metricsAttemptSpeedMax;
        final double speedMaxXZ = this.metricsAttemptSpeedMaxXZ;
        final double speedSum = this.metricsAttemptSpeedSum;
        final double speedSumXZ = this.metricsAttemptSpeedSumXZ;

        final BetterBlockPos landingSpot = this.landingSpot;
        final State stateEnd = this.state;

        final boolean playerFallFlyingEnd = ctx.player() != null && ctx.player().isFallFlying();
        final boolean playerOnGroundEnd = ctx.player() != null && ctx.player().onGround();

        final String lostControlTo = this.metricsAttemptLostControlTo;
        final String lostControlToClass = this.metricsAttemptLostControlToClass;
        final String lostControlSource = this.metricsAttemptLostControlSource;

        double endDistSqTmp = Double.NaN;
        double endDistSqXZTmp = Double.NaN;
        if (endPos != null && destination != null) {
            Vec3 dest = center(destination);
            endDistSqTmp = distSq(endPos, dest);
            endDistSqXZTmp = distSqXZ(endPos, dest);
        }
        final double endDistSq = endDistSqTmp;
        final double endDistSqXZ = endDistSqXZTmp;

        double avgSpeed = ticks > 0 ? (speedSum / ticks) : Double.NaN;
        double avgSpeedXZ = ticks > 0 ? (speedSumXZ / ticks) : Double.NaN;

        final boolean overshoot = !Double.isInfinite(minDistSq)
            && !Double.isNaN(endDistSq)
            && Math.sqrt(minDistSq) <= 12.0
            && (Math.sqrt(endDistSq) - Math.sqrt(minDistSq)) >= 8.0;

        this.metricsAttemptActive = false;
        this.metricsAttemptStartNanos = 0L;
        this.metricsAttemptDestination = null;
        this.metricsAttemptStartDimension = null;
        this.metricsAttemptStartPos = null;
        this.metricsAttemptStartDistSq = Double.NaN;
        this.metricsAttemptStartDistSqXZ = Double.NaN;
        this.metricsAttemptMinDistSq = Double.POSITIVE_INFINITY;
        this.metricsAttemptMinDistSqXZ = Double.POSITIVE_INFINITY;
        this.metricsAttemptMinDistTick = -1;
        this.metricsAttemptTicks = 0;
        this.metricsAttemptGlideTicks = 0;
        this.metricsAttemptSpeedSum = 0.0;
        this.metricsAttemptSpeedMax = 0.0;
        this.metricsAttemptSpeedSumXZ = 0.0;
        this.metricsAttemptSpeedMaxXZ = 0.0;

        this.metricsAttemptLostControlTo = null;
        this.metricsAttemptLostControlToClass = null;
        this.metricsAttemptLostControlSource = null;

        this.baritone.getMetricsRecorder().record("elytra_end", obj -> {
            obj.addProperty("id", id);

            if (ctx.player() != null) {
                obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().toString()));
                obj.addProperty("creative", ctx.player().getAbilities().instabuild);
            }

            obj.addProperty("success", success);
            obj.addProperty("reason", reason);
            if (lostControlSource != null) {
                obj.addProperty("lost_control_source", lostControlSource);
            }
            if (lostControlTo != null) {
                obj.addProperty("lost_control_to", lostControlTo);
            }
            if (lostControlToClass != null) {
                obj.addProperty("lost_control_to_class", lostControlToClass);
            }
            obj.addProperty("time_ms", elapsedMs);
            if (destination != null) {
                obj.addProperty("dest_x", destination.getX());
                obj.addProperty("dest_y", destination.getY());
                obj.addProperty("dest_z", destination.getZ());
            }

            obj.addProperty("ticks", ticks);
            obj.addProperty("glide_ticks", glideTicks);

            if (startPos != null) {
                obj.addProperty("start_x", startPos.x);
                obj.addProperty("start_y", startPos.y);
                obj.addProperty("start_z", startPos.z);
            }
            if (!Double.isNaN(startDistSq)) {
                obj.addProperty("start_dist", Math.sqrt(startDistSq));
            }
            if (!Double.isNaN(startDistSqXZ)) {
                obj.addProperty("start_dist_xz", Math.sqrt(startDistSqXZ));
            }

            if (endPos != null) {
                obj.addProperty("end_x", endPos.x);
                obj.addProperty("end_y", endPos.y);
                obj.addProperty("end_z", endPos.z);
            }
            if (!Double.isNaN(endDistSq)) {
                obj.addProperty("end_dist", Math.sqrt(endDistSq));
            }
            if (!Double.isNaN(endDistSqXZ)) {
                obj.addProperty("end_dist_xz", Math.sqrt(endDistSqXZ));
            }

            if (!Double.isInfinite(minDistSq)) {
                obj.addProperty("min_dist", Math.sqrt(minDistSq));
            }
            if (!Double.isInfinite(minDistSqXZ)) {
                obj.addProperty("min_dist_xz", Math.sqrt(minDistSqXZ));
            }
            obj.addProperty("min_dist_tick", minDistTick);

            if (!Double.isNaN(avgSpeed)) {
                obj.addProperty("avg_speed", avgSpeed);
            }
            obj.addProperty("max_speed", speedMax);
            if (!Double.isNaN(avgSpeedXZ)) {
                obj.addProperty("avg_speed_xz", avgSpeedXZ);
            }
            obj.addProperty("max_speed_xz", speedMaxXZ);

            if (endMotion != null) {
                obj.addProperty("end_speed", endMotion.length());
                obj.addProperty("end_speed_xz", endMotion.multiply(1, 0, 1).length());
            }

            obj.addProperty("overshoot", overshoot);
            obj.addProperty("overshoot_min_le", 12.0);
            obj.addProperty("overshoot_end_minus_min_ge", 8.0);

            obj.addProperty("player_flying_end", playerFallFlyingEnd);
            obj.addProperty("player_on_ground_end", playerOnGroundEnd);

            if (landingSpot != null) {
                obj.addProperty("landing_x", landingSpot.x);
                obj.addProperty("landing_y", landingSpot.y);
                obj.addProperty("landing_z", landingSpot.z);
            }
            obj.addProperty("state_end", String.valueOf(stateEnd));
        });
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }
        if (y <= 0 || y >= 128) {
            throw new IllegalArgumentException("The y of the goal is not between 0 and 128");
        }
        this.pathTo(new BlockPos(x, y, z));
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= Baritone.settings().elytraMinFireworksBeforeLanding.value) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world: finalize telemetry (best-effort) and cleanup.
            metricsFinish(false, "worldExit");
            cancelAndCleanup();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);

        if (!this.metricsAttemptActive) {
            return;
        }
        if (ctx.player() == null) {
            return;
        }
        BlockPos destination = this.metricsAttemptDestination;
        if (destination == null) {
            return;
        }

        this.metricsAttemptTicks++;
        if (ctx.player().isFallFlying()) {
            this.metricsAttemptGlideTicks++;
        }

        Vec3 pos = ctx.player().position();
        Vec3 motion = ctx.player().getDeltaMovement();
        Vec3 dest = center(destination);

        double dSq = distSq(pos, dest);
        if (dSq < this.metricsAttemptMinDistSq) {
            this.metricsAttemptMinDistSq = dSq;
            this.metricsAttemptMinDistTick = this.metricsAttemptTicks;
        }

        double dSqXZ = distSqXZ(pos, dest);
        if (dSqXZ < this.metricsAttemptMinDistSqXZ) {
            this.metricsAttemptMinDistSqXZ = dSqXZ;
        }

        double speed = motion.length();
        this.metricsAttemptSpeedSum += speed;
        if (speed > this.metricsAttemptSpeedMax) {
            this.metricsAttemptSpeedMax = speed;
        }

        double speedXZ = motion.multiply(1, 0, 1).length();
        this.metricsAttemptSpeedSumXZ += speedXZ;
        if (speedXZ > this.metricsAttemptSpeedMaxXZ) {
            this.metricsAttemptSpeedMaxXZ = speedXZ;
        }
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IBaritone baritone) {
            super(baritone, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(BlockPos pos) {
        return pos.getY() >= 0 && pos.getY() < 128;
    }

    private boolean isSafeBlock(Block block) {
        return block == Blocks.NETHERRACK || block == Blocks.GRAVEL || (block == Blocks.NETHER_BRICKS && Baritone.settings().elytraAllowLandOnNetherFortress.value);
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= 0) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (block != Blocks.AIR) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private static final int LANDING_COLUMN_HEIGHT = 15;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        Queue<BetterBlockPos> queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - start.x) * (pos.x - start.x) + (pos.z - start.z) * (pos.z - start.z)).thenComparingInt(pos -> -pos.y));
        Set<BetterBlockPos> visited = new HashSet<>();
        LongOpenHashSet checkedPositions = new LongOpenHashSet();
        queue.add(start);

        while (!queue.isEmpty()) {
            BetterBlockPos pos = queue.poll();
            if (ctx.world().isLoaded(pos) && isInBounds(pos) && ctx.world().getBlockState(pos).getBlock() == Blocks.AIR) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, checkedPositions);
                if (actualLandingSpot != null && isColumnAir(actualLandingSpot, LANDING_COLUMN_HEIGHT) && hasAirBubble(actualLandingSpot.above(LANDING_COLUMN_HEIGHT)) && !badLandingSpots.contains(actualLandingSpot.above(LANDING_COLUMN_HEIGHT))) {
                    return actualLandingSpot.above(LANDING_COLUMN_HEIGHT);
                }
                if (visited.add(pos.north())) queue.add(pos.north());
                if (visited.add(pos.east())) queue.add(pos.east());
                if (visited.add(pos.south())) queue.add(pos.south());
                if (visited.add(pos.west())) queue.add(pos.west());
                if (visited.add(pos.above())) queue.add(pos.above());
                if (visited.add(pos.below())) queue.add(pos.below());
            }
        }
        return null;
    }
}
