package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.metrics.MetricsRecorder;

import net.minecraft.SharedConstants;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public final class MetricsCommand extends Command {

    public MetricsCommand(IBaritone baritone) {
        super(baritone, "metrics");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(2);

        MetricsRecorder recorder = getRecorder();
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "status";

        try {
            switch (action) {
                case "start" -> {
                    final boolean started = recorder.start();
                    logDirect("Metrics recording: ON");
                    logDirect("File: " + recorder.getFile().toAbsolutePath());
                    if (recorder.getSessionId() != null) {
                        logDirect("Session: " + recorder.getSessionId());
                    }
                    if (started) {
                        recorder.record("session_start", obj -> {
                            obj.addProperty("mc_version", SharedConstants.getCurrentVersion().name());
                            obj.addProperty("baritone_version", baritoneVersion());

                            obj.addProperty("setting_primaryTimeoutMS", Baritone.settings().primaryTimeoutMS.value);
                            obj.addProperty("setting_failureTimeoutMS", Baritone.settings().failureTimeoutMS.value);
                            obj.addProperty("setting_planAheadPrimaryTimeoutMS", Baritone.settings().planAheadPrimaryTimeoutMS.value);
                            obj.addProperty("setting_planAheadFailureTimeoutMS", Baritone.settings().planAheadFailureTimeoutMS.value);
                            obj.addProperty("setting_elytraPredictTerrain", Baritone.settings().elytraPredictTerrain.value);
                            obj.addProperty("setting_elytraTermsAccepted", Baritone.settings().elytraTermsAccepted.value);

                            if (ctx.player() != null) {
                                obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().location()));
                                obj.addProperty("creative", ctx.player().getAbilities().instabuild);
                            }
                        });
                    }
                }
                case "stop" -> {
                    recorder.stop();
                    logDirect("Metrics recording: OFF");
                }
                case "flush" -> {
                    recorder.flush();
                    logDirect("Flushed");
                }
                case "reset" -> {
                    recorder.reset();
                    logDirect("Reset (file deleted): " + recorder.getFile().toAbsolutePath());
                }
                case "path" -> {
                    Path p = recorder.getFile().toAbsolutePath();
                    logDirect("File: " + p);
                }
                case "status" -> {
                    logDirect("Metrics recording: " + (recorder.isRunning() ? "ON" : "OFF"));
                    logDirect("File: " + recorder.getFile().toAbsolutePath());
                    if (recorder.getSessionId() != null) {
                        logDirect("Session: " + recorder.getSessionId());
                    }
                    logDirect("Events written: " + recorder.getEventsWritten());
                }
                case "mark" -> {
                    if (!recorder.isRunning()) {
                        throw new CommandInvalidStateException("Metrics recording is OFF (use #metrics start)");
                    }
                    final String markLabel = args.getString();
                    recorder.record("mark", obj -> {
                        obj.addProperty("label", markLabel);
                        if (ctx.player() != null) {
                            obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().location()));
                            obj.addProperty("creative", ctx.player().getAbilities().instabuild);
                        }
                    });
                    logDirect("Mark: " + markLabel);
                }
                default -> throw new CommandInvalidTypeException(args.consumed(), "one of start|stop|flush|reset|status|path|mark");
            }
        } catch (IOException e) {
            throw new CommandInvalidStateException("I/O error: " + e.getMessage());
        }
    }

    private MetricsRecorder getRecorder() throws CommandInvalidStateException {
        if (this.baritone instanceof Baritone impl) {
            return impl.getMetricsRecorder();
        }
        throw new CommandInvalidStateException("Metrics are only available in the built-in Baritone implementation");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        if (args.hasExactlyOne()) {
            return Stream.of("start", "stop", "flush", "reset", "status", "path", "mark");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Control Baritone metrics recording";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Controls the opt-in JSONL metrics recorder.",
                "",
                "Usage:",
                "> metrics status - Show status and output file",
                "> metrics start - Start recording",
                "> metrics stop - Stop recording",
                "> metrics flush - Flush buffered output",
                "> metrics reset - Delete the metrics file",
                "> metrics path - Print output file path",
                "> metrics mark <label> - Write a marker event (to segment runs)"
        );
    }

    private static String baritoneVersion() {
        Package p = Baritone.class.getPackage();
        if (p != null) {
            String v = p.getImplementationVersion();
            if (v != null && !v.isBlank()) {
                return v;
            }
        }

        String loaderVersion = loaderReportedBaritoneVersion();
        return loaderVersion == null ? "-" : loaderVersion;
    }

    private static String loaderReportedBaritoneVersion() {
        // Fabric
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object fabricLoader = fabricLoaderClass.getMethod("getInstance").invoke(null);
            Object maybeContainer = fabricLoaderClass.getMethod("getModContainer", String.class).invoke(fabricLoader, "baritone");
            if (maybeContainer instanceof Optional<?> opt && opt.isPresent()) {
                Object container = opt.get();
                Object metadata = container.getClass().getMethod("getMetadata").invoke(container);
                Object version = metadata.getClass().getMethod("getVersion").invoke(metadata);
                Object friendly = version.getClass().getMethod("getFriendlyString").invoke(version);
                if (friendly instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }

        // Forge
        String forge = tryForgeLikeModList("net.minecraftforge.fml.ModList");
        if (forge != null) {
            return forge;
        }

        // NeoForge
        return tryForgeLikeModList("net.neoforged.fml.ModList");
    }

    private static String tryForgeLikeModList(String modListClassName) {
        try {
            Class<?> modListClass = Class.forName(modListClassName);
            Object modList = modListClass.getMethod("get").invoke(null);

            Object maybeContainer = modListClass.getMethod("getModContainerById", String.class).invoke(modList, "baritone");
            if (maybeContainer instanceof Optional<?> opt && opt.isPresent()) {
                Object container = opt.get();
                Object modInfo = container.getClass().getMethod("getModInfo").invoke(container);
                Object version = modInfo.getClass().getMethod("getVersion").invoke(modInfo);
                String s = String.valueOf(version);
                if (!s.isBlank() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
