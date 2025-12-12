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
                this.metricsAttemptLostControlSource = "unknown";
            }
        }
        metricsFinish(false, "lostControl");
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.reachedGoal = false;
        this.goal = null;
        destroyBehaviorAsync();
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
        final long seedSetting = Baritone.settings().elytraNetherSeed.value;
        if (seedSetting != this.behavior.context.getSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
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
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (ctx.player().position().distanceToSqr(last.getCenter()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                logDirect("Path complete, picking a nearby safe landing spot...");
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // if this fails we will just keep orbiting the last node until we run out of rockets or the user intervenes
                if (landingSpot != null) {
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                }
                this.goingToLandingSpot = true;
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
                        clientLevel.disconnect(Component.literal("[Baritone] Arrived at goal!"));
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
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
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
        if (ctx.player() == null || ctx.player().level().dimension() != Level.NETHER) {
            return;
        }
        this.onLostControl();
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
                obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().location()));
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
                obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().location()));
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
            // Exiting the world, just destroy
            destroyBehaviorAsync();
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
