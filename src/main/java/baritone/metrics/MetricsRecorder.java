package baritone.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import baritone.api.utils.IPlayerContext;
import net.minecraft.resources.ResourceKey;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class MetricsRecorder {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final AtomicLong eventsWritten = new AtomicLong();

    private BufferedWriter writer;
    private String sessionId;

    private volatile IPlayerContext playerContext;
    private String pendingCommandContextJson;

    public MetricsRecorder(Path baritoneDirectory) {
        Objects.requireNonNull(baritoneDirectory, "baritoneDirectory");
        this.file = baritoneDirectory.resolve("metrics.jsonl");
    }

    public void setPlayerContext(IPlayerContext playerContext) {
        this.playerContext = playerContext;
    }

    public Path getFile() {
        return this.file;
    }

    public synchronized boolean isRunning() {
        return this.writer != null;
    }

    public synchronized String getSessionId() {
        return this.sessionId;
    }

    public long getEventsWritten() {
        return this.eventsWritten.get();
    }

    public synchronized boolean start() throws IOException {
        if (this.writer != null) {
            return false;
        }
        Files.createDirectories(this.file.getParent());
        this.writer = Files.newBufferedWriter(
                this.file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        this.sessionId = UUID.randomUUID().toString();
        return true;
    }

    public synchronized void stop() throws IOException {
        if (this.writer == null) {
            return;
        }
        try {
            this.writer.flush();
        } finally {
            this.writer.close();
            this.writer = null;
            this.sessionId = null;
        }
    }

    public synchronized void flush() throws IOException {
        if (this.writer == null) {
            return;
        }
        this.writer.flush();
    }

    public synchronized void reset() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
            this.writer.close();
            this.writer = null;
        }
        this.sessionId = null;
        this.eventsWritten.set(0L);
        this.pendingCommandContextJson = null;
        Files.deleteIfExists(this.file);
    }

    public void record(String type, Consumer<JsonObject> builder) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(builder, "builder");

        BufferedWriter out;
        String sid;
        synchronized (this) {
            out = this.writer;
            sid = this.sessionId;
        }
        if (out == null) {
            return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("ts", System.currentTimeMillis());
        obj.addProperty("type", type);
        if (sid != null) {
            obj.addProperty("session_id", sid);
        }

        // Common context (best-effort; must never crash gameplay)
        IPlayerContext ctx = this.playerContext;
        if (ctx != null) {
            try {
                if (ctx.player() != null) {
                    obj.addProperty("dimension", String.valueOf(ctx.player().level().dimension().toString()));
                    obj.addProperty("creative", ctx.player().getAbilities().instabuild);
                }

                boolean integrated = false;
                if (ctx.minecraft() != null) {
                    try {
                        integrated = ctx.minecraft().hasSingleplayerServer();
                    } catch (Throwable ignored) {
                        // ignore; fall through
                    }
                }
                obj.addProperty("is_integrated_server", integrated);
                obj.addProperty("server_type", integrated ? "singleplayer" : "multiplayer");
            } catch (Throwable ignored) {
                // ignore
            }
        }

        builder.accept(obj);

        String line = GSON.toJson(obj);
        synchronized (this) {
            if (this.writer == null) {
                return;
            }
            try {
                this.writer.write(line);
                this.writer.newLine();
                this.eventsWritten.incrementAndGet();
            } catch (IOException ignored) {
                // metrics must never crash gameplay
            }
        }
    }

    /**
     * Sets a one-shot context object that can be consumed by the next {@code path_start} event.
     * Intended for attaching command details (e.g., goto/follow args) to the resulting path.
     */
    public void setPendingCommandContext(String command, Consumer<JsonObject> builder) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(builder, "builder");

        JsonObject obj = new JsonObject();
        obj.addProperty("command", command);
        try {
            builder.accept(obj);
        } catch (Throwable ignored) {
            // best-effort
        }

        synchronized (this) {
            // store as json to avoid sharing a mutable JsonObject across threads
            this.pendingCommandContextJson = GSON.toJson(obj);
        }
    }

    /**
     * Consumes and clears the pending command context.
     */
    public JsonObject consumePendingCommandContext() {
        String json;
        synchronized (this) {
            json = this.pendingCommandContextJson;
            this.pendingCommandContextJson = null;
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
