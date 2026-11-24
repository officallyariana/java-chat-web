import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatServer implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 16_384;
    private static final int SOCKET_READ_TIMEOUT_MS = 30_000;
    private static final int HISTORY_LIMIT = 20;
    private static final int MAX_CLIENT_THREADS = Math.max(16, Runtime.getRuntime().availableProcessors() * 4);

    private final int port;
    private final HistoryStore history;
    private final ConcurrentHashMap<String, Session> online = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<Session>> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> roomNames = new ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newFixedThreadPool(MAX_CLIENT_THREADS);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedMessages = new AtomicLong();
    private final AtomicLong attemptedDeliveries = new AtomicLong();
    private final AtomicLong successfulDeliveries = new AtomicLong();
    private final AtomicLong detectedSendFailures = new AtomicLong();
    private ServerSocket listener;

    private static final ThreadLocal<CharsetDecoder> UTF8_DECODER = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));

    private static final CopyOnWriteArraySet<Session> EMPTY_ROOM = new CopyOnWriteArraySet<>();

    public ChatServer(int port, Path storage) throws IOException {
        this.port = port;
        this.history = new HistoryStore(storage);
        rooms.put("lobby", new CopyOnWriteArraySet<>());
        roomNames.put("lobby", "lobby");
    }

    private static void log(String category, String message) {
        System.out.printf("[%s] [%s] %s%n", Instant.now(), category, message);
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running or has been closed");
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port));
        log("SERVER", "Listening on TCP port " + listener.getLocalPort());
        try {
            while (running.get()) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (SocketException e) {
                    if (running.get()) throw e;
                    break;
                }
                try {
                    clients.execute(() -> serve(socket));
                } catch (RejectedExecutionException e) {
                    socket.close();
                    if (running.get()) log("ERROR", "Client worker rejected");
                }
            }
        } finally {
            close();
        }
    }

    private static String readFrame(InputStream input) throws IOException {
        byte[] buf = new byte[MAX_FRAME_BYTES + 1];
        int len = 0;
        int ch;
        while ((ch = input.read()) != -1) {
            if (ch == '\n') break;
            if (len >= MAX_FRAME_BYTES) {
                throw new FrameException("Message exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            buf[len++] = (byte) ch;
        }
        if (ch == -1 && len == 0) return null;
        if (ch == -1) throw new EOFException("Connection closed during a message");
        if (len > 0 && buf[len - 1] == '\r') len--;
        try {
            return UTF8_DECODER.get().decode(ByteBuffer.wrap(buf, 0, len)).toString();
        } catch (CharacterCodingException e) {
            throw new FrameException("Invalid UTF-8 message");
        }
    }

    private static final class FrameException extends IOException {
        FrameException(String message) { super(message); }
    }

    private void serve(Socket socket) {
        Session session = null;
        try (socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             PrintWriter output = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
            String hello = readFrame(input);
            if (hello == null) return;
            String[] init;
            try {
                init = Protocol.decode(hello);
            } catch (IllegalArgumentException e) {
                output.println(Protocol.encode("ERROR", "Malformed HELLO message."));
                return;
            }
            if (init.length != 2 || !init[0].equals("HELLO") || !validName(init[1])) {
                output.println(Protocol.encode("ERROR", "Send HELLO with a username (1-20 letters, numbers, _ or -)."));
                return;
            }
            String userKey = key(init[1]);
            if (online.containsKey(userKey)) {
                output.println(Protocol.encode("ERROR", "Username already online."));
                log("REJECT", "Duplicate username: " + init[1]);
                return;
            }
            session = new Session(init[1], socket, output);
            Session existing = online.putIfAbsent(userKey, session);
            if (existing != null) {
                output.println(Protocol.encode("ERROR", "Username already online."));
                log("REJECT", "Duplicate username: " + init[1]);
                return;
            }
            rooms.get("lobby").add(session);
            socket.setSoTimeout(0);
            session.send("WELCOME", session.name, "lobby");
            announce("lobby", session.name + " joined the lobby.", session);
            log("CONNECT", session.name + " connected; active users=" + online.size());
            while (true) {
                String line = readFrame(input);
                if (line == null) break;
                try {
                    dispatch(session, Protocol.decode(line));
                } catch (IllegalArgumentException e) {
                    log("REJECT", "Malformed command from " + session.name);
                    session.send("ERROR", "Malformed command.");
                }
            }
        } catch (FrameException e) {
            log("REJECT", "Invalid frame from " + (session == null ? socket.getRemoteSocketAddress() : session.name)
                    + ": " + e.getMessage());
        } catch (IOException e) {
            log("DISCONNECT", (session == null ? "Unregistered connection" : session.name) + ": " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log("ERROR", "Client handler failed: " + e.getClass().getSimpleName());
        } finally {
            if (session != null) disconnect(session);
        }
    }

    private static String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private static boolean validName(String s) { return s.matches("[A-Za-z0-9_-]{1,20}"); }
    private static boolean validRoom(String s) { return s.matches("[A-Za-z0-9_-]{1,30}"); }
    private static boolean validText(String s) { return !s.isBlank() && s.length() <= 2000; }
    private static boolean validRequestId(String s) { return s.matches("[A-Za-z0-9_-]{1,64}"); }

    private void dispatch(Session user, String[] c) {
        if (c.length == 0) { user.send("ERROR", "Empty command."); return; }
        switch (c[0]) {
            case "JOIN" -> handleJoin(user, c);
            case "SAY" -> handleSay(user, c);
            case "DM" -> handleDm(user, c);
            case "ROOMS" -> handleRooms(user, c);
            case "USERS" -> handleUsers(user, c);
            case "HISTORY" -> handleHistory(user, c);
            case "DMHISTORY" -> handleDmHistory(user, c);
            case "QUIT" -> handleQuit(user, c);
            default -> user.send("ERROR", "Unknown command. Try /help in the client.");
        }
    }

    private void handleJoin(Session user, String[] c) {
        if (c.length != 2 || !validRoom(c[1])) {
            user.send("ERROR", "Room names: 1-30 letters, numbers, _ or -.");
            return;
        }
        String old, room, k = key(c[1]);
        synchronized (this) {
            old = user.room;
            rooms.get(old).remove(user);
            roomNames.putIfAbsent(k, c[1]);
            rooms.computeIfAbsent(k, ignored -> new CopyOnWriteArraySet<>()).add(user);
            user.room = k;
            room = roomNames.get(k);
        }
        user.send("JOINED", room);
        if (!old.equals(k)) {
            announce(old, user.name + " left the room.", user);
            announce(k, user.name + " joined the room.", user);
        }
        log("ROOM", user.name + " joined " + room);
    }

    private void handleSay(Session user, String[] c) {
        boolean withId = c.length == 3;
        String id = withId ? c[1] : "";
        String message = withId ? c[2] : c.length == 2 ? c[1] : "";
        if ((c.length != 2 && c.length != 3) || (withId && !validRequestId(id)) || !validText(message)) {
            user.send("ERROR", "Message must have 1-2000 characters; optional ID must be 1-64 safe characters.");
            return;
        }
        String room = user.room;
        String displayRoom = roomNames.get(room);
        try {
            history.addRoom(room, user.name, message);
            int sent = broadcast(room, "ROOM_MSG", displayRoom, user.name, message);
            long number = processedMessages.incrementAndGet();
            log("MESSAGE", "#" + number + " room=" + room + " from=" + user.name + " write-success=" + sent);
            if (withId) user.send("ACK", id, "SAY", "STORED");
        } catch (IOException e) {
            log("ERROR", "Room history save failed for " + user.name);
            user.send("ERROR", "Could not save message.");
        }
    }

    private void handleDm(Session user, String[] c) {
        boolean withId = c.length == 4;
        String id = withId ? c[1] : "";
        String targetName = withId ? c[2] : c.length == 3 ? c[1] : "";
        String message = withId ? c[3] : c.length == 3 ? c[2] : "";
        if ((c.length != 3 && c.length != 4) || (withId && !validRequestId(id))
                || !validName(targetName) || !validText(message)) {
            user.send("ERROR", "Usage: DM [optional ID] USER MESSAGE");
            return;
        }
        Session target = online.get(key(targetName));
        if (target == null) { user.send("ERROR", "That user is not online."); return; }
        try {
            history.addPrivate(user.name, target.name, user.name, message);
            int sent = user.send("DM_MSG", user.name, target.name, message) ? 1 : 0;
            if (target != user && target.send("DM_MSG", user.name, target.name, message)) sent++;
            attemptedDeliveries.addAndGet(2);
            successfulDeliveries.addAndGet(sent);
            long number = processedMessages.incrementAndGet();
            log("MESSAGE", "#" + number + " DM from=" + user.name + " to=" + target.name + " write-success=" + sent);
            if (withId) user.send("ACK", id, "DM", "STORED");
        } catch (IOException e) {
            log("ERROR", "DM history save failed for " + user.name);
            user.send("ERROR", "Could not save private message.");
        }
    }

    private void handleRooms(Session user, String[] c) {
        if (c.length != 1) { user.send("ERROR", "Usage: /rooms"); return; }
        List<String> names = new ArrayList<>(roomNames.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        user.send("ROOMS", String.join(", ", names));
    }

    private void handleUsers(Session user, String[] c) {
        if (c.length != 1) { user.send("ERROR", "Usage: /users"); return; }
        List<String> names = online.values().stream()
                .map(s -> s.name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        user.send("USERS", String.join(", ", names));
    }

    private void handleHistory(Session user, String[] c) {
        if (c.length != 1) { user.send("ERROR", "Usage: /history"); return; }
        String room = user.room;
        String displayRoom = roomNames.get(room);
        try {
            user.send("HISTORY_START", "room", displayRoom);
            for (String[] msg : history.room(room, HISTORY_LIMIT)) user.send("HISTORY_MSG", msg[0], msg[1], msg[2]);
            user.send("HISTORY_END");
        } catch (IOException e) { user.send("ERROR", "Could not load history."); }
    }

    private void handleDmHistory(Session user, String[] c) {
        if (c.length != 2 || !validName(c[1])) { user.send("ERROR", "Usage: /dmhistory USER"); return; }
        try {
            user.send("HISTORY_START", "private", c[1]);
            for (String[] msg : history.privateMessages(user.name, c[1], HISTORY_LIMIT)) user.send("HISTORY_MSG", msg[0], msg[1], msg[2]);
            user.send("HISTORY_END");
        } catch (IOException e) { user.send("ERROR", "Could not load history."); }
    }

    private void handleQuit(Session user, String[] c) {
        if (c.length != 1) { user.send("ERROR", "Usage: /quit"); return; }
        user.send("INFO", "Goodbye.");
        try { user.socket.close(); } catch (IOException ignored) { }
    }

    private void announce(String room, String message, Session except) {
        CopyOnWriteArraySet<Session> targets = rooms.getOrDefault(room, EMPTY_ROOM);
        for (Session s : targets) if (s != except) s.send("INFO", message);
    }

    private int broadcast(String room, String... fields) {
        CopyOnWriteArraySet<Session> targets = rooms.getOrDefault(room, EMPTY_ROOM);
        int deliveredToSocket = 0;
        for (Session s : targets) if (s.send(fields)) deliveredToSocket++;
        attemptedDeliveries.addAndGet(targets.size());
        successfulDeliveries.addAndGet(deliveredToSocket);
        return deliveredToSocket;
    }

    private void disconnect(Session user) {
        boolean removed;
        String room;
        synchronized (this) {
            removed = online.remove(key(user.name), user);
            room = user.room;
            if (removed) {
                Set<Session> roomSet = rooms.get(room);
                if (roomSet != null) roomSet.remove(user);
            }
        }
        if (removed) {
            announce(room, user.name + " disconnected.", user);
            log("DISCONNECT", user.name + "; active users=" + online.size());
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        listener = null;
        List<Session> sessions;
        synchronized (this) {
            sessions = new ArrayList<>(online.values());
            online.clear();
            rooms.values().forEach(Set::clear);
            rooms.clear();
        }
        for (Session s : sessions) try { s.socket.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        try { clients.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { history.close(); } catch (IOException ignored) { }
    }

    private final class Session {
        final String name;
        final Socket socket;
        final PrintWriter out;
        volatile String room = "lobby";
        Session(String name, Socket socket, PrintWriter out) {
            this.name = name;
            this.socket = socket;
            this.out = out;
        }
        synchronized boolean send(String... fields) {
            if (socket.isClosed()) return false;
            out.println(Protocol.encode(fields));
            if (out.checkError()) {
                detectedSendFailures.incrementAndGet();
                log("SEND_ERROR", "Socket write failed for " + name);
                try { socket.close(); } catch (IOException ignored) { }
                return false;
            }
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 5555;
        Path data = Path.of("data");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--data") && i + 1 < args.length) data = Path.of(args[++i]);
            else { System.err.println("Usage: java ChatServer [--port 5555] [--data data]"); return; }
        }
        ChatServer server = new ChatServer(port, data);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
