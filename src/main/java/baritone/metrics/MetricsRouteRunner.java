package baritone.metrics;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IElytraProcess;
import baritone.api.utils.Helper;
import baritone.behavior.Behavior;
import baritone.process.elytra.ElytraBehavior;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tick-driven helper to run a sequence of Elytra flights and auto-write metrics marks.
 *
 * Started by {@link baritone.command.defaults.MetricsCommand}.
 */
public final class MetricsRouteRunner extends Behavior implements Helper {

    private enum State {
        IDLE,
        START_NEXT,
        WAIT_ACTIVE,
        WAIT_GLIDE,
        WAIT_DONE
    }

    private final MetricsRecorder recorder;

    private State state = State.IDLE;
    private List<Goal> route = List.of();
    private int index = 0;

    private int ticksWaitingForActive = 0;
    private int ticksWaitingForGlide = 0;
    private int ticksWaitingForDone = 0;

    private int ticksOnGroundStable = 0;

    private boolean prevElytraAutoJump;
    private boolean overrideElytraAutoJump;

    public MetricsRouteRunner(Baritone baritone) {
        super(baritone);
        this.recorder = baritone.getMetricsRecorder();
    }

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public void startRoute(List<Goal> goals) {
        if (goals == null || goals.isEmpty()) {
            stopRoute("No route points provided");
            return;
        }

        // The route runner is designed to be one-command automation.
        // Force elytraAutoJump=true during a route so each leg can take off without manual intervention.
        // (We restore it when the route stops/completes.)
        this.prevElytraAutoJump = Baritone.settings().elytraAutoJump.value;
        Baritone.settings().elytraAutoJump.value = true;
        this.overrideElytraAutoJump = true;

        this.route = List.copyOf(goals);
        this.index = 0;
        this.state = State.START_NEXT;
        this.ticksWaitingForActive = 0;
        this.ticksWaitingForGlide = 0;
        this.ticksWaitingForDone = 0;
        this.ticksOnGroundStable = 0;
        logDirect("Metrics route: starting (" + this.route.size() + " legs)");
    }

    public void stopRoute(String reason) {
        if (!isRunning()) {
            return;
        }
        this.state = State.IDLE;
        this.route = List.of();
        this.index = 0;
        this.ticksWaitingForActive = 0;
        this.ticksWaitingForGlide = 0;
        this.ticksWaitingForDone = 0;
        this.ticksOnGroundStable = 0;

        if (this.overrideElytraAutoJump) {
            Baritone.settings().elytraAutoJump.value = this.prevElytraAutoJump;
            this.overrideElytraAutoJump = false;
        }

        try {
            if (recorder.isRunning()) {
                recorder.flush();
                recorder.stop();
            }
        } catch (IOException e) {
            logDirect("Metrics route: stopped (" + reason + "), but failed to stop/flush: " + e.getMessage());
            return;
        }
        logDirect("Metrics route: stopped (" + reason + ") (metrics recording: OFF)");
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (!isRunning()) {
            return;
        }

        // If recording was manually stopped mid-route, stop the runner too.
        if (!recorder.isRunning()) {
            stopRoute("metrics recording is OFF");
            return;
        }
        if (ctx.player() == null || ctx.world() == null) {
            stopRoute("no world/player");
            return;
        }

        final IElytraProcess elytra = baritone.getElytraProcess();
        if (!elytra.isLoaded()) {
            stopRoute("elytra native library not loaded");
            return;
        }

        switch (state) {
            case START_NEXT -> {
                if (elytra.isActive()) {
                    // Wait for whatever is currently active to finish first.
                    return;
                }

                if (Baritone.settings().elytraPredictTerrain.value && ctx.player().level().dimension() != Level.NETHER) {
                    stopRoute("elytraPredictTerrain is nether-only (set it to false for overworld)");
                    return;
                }

                // Avoid starting a new leg while we're still falling / stabilizing after a landing.
                // This prevents a bunch of confusing edge cases (starting while midair, starting while still being pushed).
                if (!ctx.player().onGround()) {
                    this.ticksOnGroundStable = 0;
                    return;
                }
                this.ticksOnGroundStable++;
                if (this.ticksOnGroundStable < 5) {
                    return;
                }

                if (index >= route.size()) {
                    finishRoute();
                    return;
                }

                // Overworld long-distance flights without rockets are effectively a no-op.
                // Fail early with a clear message rather than silently gliding nowhere.
                if (!hasUsableElytraAndFireworks()) {
                    stopRoute("need an elytra equipped and fireworks in inventory for route automation");
                    return;
                }

                // Ensure no other Baritone processes interfere with the Elytra leg.
                baritone.getPathingBehavior().cancelEverything();

                final String markLabel = String.format(Locale.US, "fly_%d", index + 1);
                recorder.record("mark", obj -> obj.addProperty("label", markLabel));

                final Goal goal = route.get(index);

                try {
                    elytra.pathTo(goal);
                } catch (IllegalArgumentException ex) {
                    stopRoute(ex.getMessage());
                    return;
                }

                logDirect("Metrics route: started " + markLabel + " -> " + goal);
                this.ticksWaitingForActive = 0;
                this.ticksWaitingForGlide = 0;
                this.ticksWaitingForDone = 0;
                this.ticksOnGroundStable = 0;
                this.state = State.WAIT_ACTIVE;
            }
            case WAIT_ACTIVE -> {
                if (elytra.isActive()) {
                    this.state = State.WAIT_GLIDE;
                    return;
                }
                this.ticksWaitingForActive++;
                // If Elytra doesn't become active in a reasonable window, fail fast so you don't get stuck.
                if (this.ticksWaitingForActive > 200) {
                    stopRoute("elytra did not start");
                }
            }
            case WAIT_GLIDE -> {
                // Elytra process can be active while we're still on the ground (waiting to walk off / start gliding).
                // For route automation we require actual gliding to start, otherwise legs will appear to "do nothing".
                if (!elytra.isActive()) {
                    // Elytra process died before we ever started gliding.
                    stopRoute("elytra stopped before gliding started");
                    return;
                }
                if (ctx.player().isFallFlying()) {
                    this.state = State.WAIT_DONE;
                    return;
                }
                this.ticksWaitingForGlide++;
                if (this.ticksWaitingForGlide > 200) {
                    stopRoute("elytra did not start gliding (try an exposed ledge, or start gliding manually)");
                }
            }
            case WAIT_DONE -> {
                if (!elytra.isActive()) {
                    // Small debounce to avoid edge cases where it flips inactive briefly.
                    this.ticksWaitingForDone++;
                    if (this.ticksWaitingForDone > 5) {
                        // Wait until we are stably on the ground before starting the next leg.
                        if (ctx.player().onGround()) {
                            this.ticksOnGroundStable++;
                            if (this.ticksOnGroundStable >= 5) {
                                this.index++;
                                this.state = State.START_NEXT;
                                this.ticksOnGroundStable = 0;
                            }
                        } else {
                            this.ticksOnGroundStable = 0;
                        }
                    }
                    return;
                }
                this.ticksWaitingForDone = 0;
                this.ticksOnGroundStable = 0;
            }
            case IDLE -> {
                // no-op
            }
        }
    }

    private void finishRoute() {
        try {
            recorder.flush();
            recorder.stop();
        } catch (IOException e) {
            // Best-effort; still consider the route completed.
            logDirect("Metrics route: completed, but failed to stop/flush: " + e.getMessage());
        }

        if (this.overrideElytraAutoJump) {
            Baritone.settings().elytraAutoJump.value = this.prevElytraAutoJump;
            this.overrideElytraAutoJump = false;
        }

        this.state = State.IDLE;
        this.route = List.of();
        this.index = 0;
        logDirect("Metrics route: complete (metrics recording: OFF)");
    }

    private boolean hasUsableElytraAndFireworks() {
        if (ctx.player() == null) {
            return false;
        }
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest == null || chest.isEmpty() || chest.getItem() != Items.ELYTRA) {
            return false;
        }
        if (chest.getMaxDamage() - chest.getDamageValue() < Baritone.settings().elytraMinimumDurability.value) {
            return false;
        }

        int fireworks = 0;
        // NonNullList<ItemStack> inv = ctx.player().getInventory().items; // This will fail compilation, we need to iterate or use getter
        for (int i = 0; i < ctx.player().getInventory().getContainerSize(); i++) {
            ItemStack st = ctx.player().getInventory().getItem(i);
            if (ElytraBehavior.isFireworks(st)) {
                fireworks += st.getCount();
            }
        }
        return fireworks > Baritone.settings().elytraMinFireworksBeforeLanding.value;
    }

    public static List<Goal> parseRouteXZ(List<Integer> coords) {
        if (coords.size() % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of integers (x z pairs)");
        }
        List<Goal> goals = new ArrayList<>(coords.size() / 2);
        for (int i = 0; i < coords.size(); i += 2) {
            goals.add(new GoalXZ(coords.get(i), coords.get(i + 1)));
        }
        return goals;
    }
}
