import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HistoryStore implements AutoCloseable {
    private final Path root;
    private final Map<Path, BufferedWriter> writers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher;

    public HistoryStore(Path root) throws IOException {
        this.root = root.toAbsolutePath();
        Files.createDirectories(this.root);
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleAtFixedRate(this::flushAll, 5, 5, TimeUnit.SECONDS);
    }

    private Path path(String type, String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return root.resolve(type + "_" + encoded + ".log");
    }

    private String roomKey(String room) { return room.toLowerCase(java.util.Locale.ROOT); }

    private String dmKey(String a, String b) {
        String[] pair = {a.toLowerCase(java.util.Locale.ROOT), b.toLowerCase(java.util.Locale.ROOT)};
        java.util.Arrays.sort(pair);
        return pair[0] + "\u0000" + pair[1];
    }

    public void addRoom(String room, String sender, String text) throws IOException {
        append(path("room", roomKey(room)), sender, text);
    }

    public void addPrivate(String a, String b, String sender, String text) throws IOException {
        append(path("dm", dmKey(a, b)), sender, text);
    }

    private void append(Path file, String sender, String text) throws IOException {
        BufferedWriter w = writerFor(file);
        w.write(Protocol.encode(Instant.now().toString(), sender, text));
        w.newLine();
    }

    private BufferedWriter writerFor(Path file) {
        return writers.computeIfAbsent(file, f -> {
            try {
                return Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void flushWriter(Path file) {
        BufferedWriter w = writers.get(file);
        if (w != null) {
            try { w.flush(); } catch (IOException ignored) { }
        }
    }

    private void flushAll() {
        for (BufferedWriter w : writers.values()) {
            try { w.flush(); } catch (IOException ignored) { }
        }
    }

    public List<String[]> room(String room, int count) throws IOException {
        Path file = path("room", roomKey(room));
        flushWriter(file);
        return tail(file, count);
    }

    public List<String[]> privateMessages(String a, String b, int count) throws IOException {
        Path file = path("dm", dmKey(a, b));
        flushWriter(file);
        return tail(file, count);
    }

    private List<String[]> tail(Path file, int count) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        ArrayDeque<String[]> last = new ArrayDeque<>(count);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] msg = Protocol.decode(line);
                    if (msg.length != 3) continue;
                    if (last.size() == count) last.removeFirst();
                    last.addLast(msg);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return new ArrayList<>(last);
    }

    @Override public void close() throws IOException {
        flusher.shutdown();
        try { flusher.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        flushAll();
        List<IOException> errors = new ArrayList<>();
        for (BufferedWriter w : writers.values()) {
            try { w.close(); } catch (IOException e) { errors.add(e); }
        }
        writers.clear();
        if (!errors.isEmpty()) throw errors.get(0);
    }
}
