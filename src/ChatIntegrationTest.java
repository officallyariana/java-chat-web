import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ChatIntegrationTest {
    private static int assertions;
    private static final int TIMEOUT_MS = 4000;
    private static final long POLL_DEADLINE_MS = 5000;
    private static final long POLL_INTERVAL_MS = 25;

    public static void main(String[] args) throws Exception {
        Path storage = Files.createTempDirectory("java-chat-tests-");
        try (ServerSocket probe = new ServerSocket(0)) {
            int port = probe.getLocalPort();
            probe.close();
            try (ChatServer server = new ChatServer(port, storage)) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> active = executor.submit(() -> {
                    try { server.start(); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                try {
                    waitForServer(port);
                    basic(port);
                    validation(port);
                    framing(port);
                    concurrency(port);
                    disconnect(port);
                } finally {
                    server.close();
                    executor.shutdownNow();
                    awaitExecutor(executor, active);
                }
            }
        }
        System.out.println("\nPASS: " + assertions + " integration assertions.");
    }

    private static void waitForServer(int port) throws Exception {
        for (int i = 0; i < 80; i++) {
            try (Socket ignored = new Socket("127.0.0.1", port)) { return; }
            catch (ConnectException ignored) { Thread.sleep(25); }
        }
        throw new AssertionError("Server startup failed");
    }

    private static void awaitExecutor(ExecutorService executor, Future<?> active) throws InterruptedException {
        try { active.get(3, TimeUnit.SECONDS); }
        catch (CancellationException | ExecutionException | TimeoutException ignored) { }
        try { executor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("FAIL: " + name);
        assertions++;
        System.out.println("PASS: " + name);
    }

    private static String field(String[] event, int index, String context) {
        if (event == null || event.length <= index) {
            throw new AssertionError("Expected field [" + index + "] missing in " + context + ": " + Arrays.toString(event));
        }
        return event[index];
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final OutputStream raw;
        final BufferedReader input;
        final PrintWriter output;
        Peer(int port, String username) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            raw = socket.getOutputStream();
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8), true);
            send("HELLO", username);
        }
        void send(String... fields) {
            output.println(Protocol.encode(fields));
            if (output.checkError()) throw new AssertionError("Socket send failed");
        }
        void sendRaw(byte[] bytes) throws IOException { raw.write(bytes); raw.flush(); }
        String[] next() throws IOException {
            String line = input.readLine();
            if (line == null) throw new EOFException("Server closed connection");
            return Protocol.decode(line);
        }
        String[] until(String type) throws IOException, InterruptedException {
            long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                String[] event = next();
                if (event.length > 0 && event[0].equals(type)) return event;
                Thread.sleep(POLL_INTERVAL_MS);
            }
            throw new AssertionError("Expected " + type + " was not received before deadline");
        }
        String[] untilWithoutRoomLeak(String type) throws IOException, InterruptedException {
            long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                String[] e = next();
                if (e.length > 0 && e[0].equals("ROOM_MSG"))
                    throw new AssertionError("Wrong-room message leaked to this client");
                if (e.length > 0 && e[0].equals(type)) return e;
                Thread.sleep(POLL_INTERVAL_MS);
            }
            throw new AssertionError("Expected " + type + " before deadline");
        }
        String[] untilAck(String requestId) throws IOException, InterruptedException {
            long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                String[] event = next();
                if (event.length >= 4 && event[0].equals("ACK") && event[1].equals(requestId)) return event;
                Thread.sleep(POLL_INTERVAL_MS);
            }
            throw new AssertionError("Expected ACK " + requestId + " before deadline");
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static void awaitUserGone(Peer p, String name) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            p.send("USERS");
            String[] users = p.until("USERS");
            String list = users.length > 1 ? users[1] : "";
            if (!Arrays.asList(list.split(", ")).contains(name)) return;
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("User remained online: " + name);
    }

    private static void basic(int port) throws Exception {
        try (Peer alice = new Peer(port, "Alice"); Peer bob = new Peer(port, "Bob"); Peer charlie = new Peer(port, "Charlie")) {
            check(field(alice.until("WELCOME"), 1, "WELCOME").equals("Alice"), "Alice connected");
            check(field(bob.until("WELCOME"), 1, "WELCOME").equals("Bob"), "Bob connected");
            check(field(charlie.until("WELCOME"), 1, "WELCOME").equals("Charlie"), "Charlie connected");
            try (Peer dupe = new Peer(port, "alice")) {
                check(field(dupe.until("ERROR"), 1, "ERROR").contains("already"), "Duplicate username rejected");
            }
            alice.send("JOIN", "project");
            bob.send("JOIN", "project");
            check(field(alice.until("JOINED"), 1, "JOINED").equals("project"), "Alice joined room");
            check(field(bob.until("JOINED"), 1, "JOINED").equals("project"), "Bob joined room");
            alice.send("SAY", "Hello project room!");
            check(field(alice.until("ROOM_MSG"), 3, "ROOM_MSG").equals("Hello project room!"), "Room sender sees message");
            check(field(bob.until("ROOM_MSG"), 3, "ROOM_MSG").equals("Hello project room!"), "Room recipient sees message");
            charlie.send("USERS");
            String[] users = charlie.untilWithoutRoomLeak("USERS");
            String list = users.length > 1 ? users[1] : "";
            check(list.contains("Alice") && list.contains("Bob"), "Online users listed");
            check(!list.isEmpty(), "User list non-empty");
            charlie.send("ROOMS");
            check(field(charlie.untilWithoutRoomLeak("ROOMS"), 1, "ROOMS").contains("project"), "Room listed");
            alice.send("DM", "Charlie", "Only you should see this");
            check(field(alice.until("DM_MSG"), 3, "DM_MSG").contains("Only you"), "DM sender copy");
            check(field(charlie.until("DM_MSG"), 3, "DM_MSG").contains("Only you"), "DM reaches recipient");
            bob.send("HISTORY");
            bob.until("HISTORY_START");
            check(field(bob.until("HISTORY_MSG"), 2, "HISTORY_MSG").equals("Alice"), "Room history stored");
            bob.until("HISTORY_END");
            alice.send("DMHISTORY", "Charlie");
            alice.until("HISTORY_START");
            check(field(alice.until("HISTORY_MSG"), 2, "HISTORY_MSG").equals("Alice"), "DM history stored");
            alice.until("HISTORY_END");
            alice.send("DM", "Nobody", "ping");
            check(field(alice.until("ERROR"), 1, "ERROR").contains("not online"), "Offline DM rejected");
            alice.send("JOIN", "!invalid");
            check(field(alice.until("ERROR"), 1, "ERROR").contains("Room names"), "Invalid room rejected");
            alice.send("SAY", "ack-room-1", "Ack request");
            check(Arrays.equals(alice.untilAck("ack-room-1"),
                    new String[]{"ACK", "ack-room-1", "SAY", "STORED"}), "SAY acknowledgment matches ID");
            alice.send("DM", "ack-dm-1", "Charlie", "Ack private");
            check(Arrays.equals(alice.untilAck("ack-dm-1"),
                    new String[]{"ACK", "ack-dm-1", "DM", "STORED"}), "DM acknowledgment matches ID");
        }
        try (Peer alice = new Peer(port, "Alice")) {
            check(field(alice.until("WELCOME"), 1, "WELCOME").equals("Alice"), "Username reused after disconnect");
            alice.send("JOIN", "project");
            alice.until("JOINED");
            alice.send("HISTORY");
            alice.until("HISTORY_START");
            boolean found = false;
            long deadline = System.currentTimeMillis() + POLL_DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                String[] e = alice.next();
                if (e[0].equals("HISTORY_END")) break;
                if (e.length > 3 && e[0].equals("HISTORY_MSG") && e[3].equals("Hello project room!")) found = true;
                Thread.sleep(POLL_INTERVAL_MS);
            }
            check(found, "History persists after reconnect");
        }
    }

    private static void validation(int port) throws Exception {
        try (Peer p = new Peer(port, "Validator")) {
            p.until("WELCOME");
            p.sendRaw("not-base64!\n".getBytes(StandardCharsets.UTF_8));
            check(field(p.until("ERROR"), 1, "ERROR").contains("Malformed"), "Malformed Base64 rejected");
            p.send("USERS");
            check(field(p.until("USERS"), 1, "USERS").contains("Validator"), "Client survives malformed command");
            p.send("SAY", " ");
            check(field(p.until("ERROR"), 1, "ERROR").contains("Message"), "Blank chat rejected");
            p.send("SAY", "x".repeat(2001));
            check(field(p.until("ERROR"), 1, "ERROR").contains("Message"), "Oversized decoded chat rejected");
            p.send("SAY", "bad id", "test");
            check(field(p.until("ERROR"), 1, "ERROR").contains("ID"), "Invalid request ID rejected");
        }
        try (Peer p = new Peer(port, "HugeFrame")) {
            p.until("WELCOME");
            p.sendRaw(("X".repeat(16385) + "\n").getBytes(StandardCharsets.UTF_8));
            boolean closed = false;
            try { closed = p.input.readLine() == null; }
            catch (SocketException e) { closed = true; }
            check(closed, "Over-limit TCP frame closes connection safely");
        }
        try (Peer p = new Peer(port, "BadUtf8")) {
            p.until("WELCOME");
            p.sendRaw(new byte[]{(byte) 0xC3, (byte) 0x28, (byte) '\n'});
            boolean closed = false;
            try { closed = p.input.readLine() == null; }
            catch (SocketException e) { closed = true; }
            check(closed, "Malformed UTF-8 frame closes connection safely");
        }
        try (Peer p = new Peer(port, "StillAlive")) {
            check(field(p.until("WELCOME"), 1, "WELCOME").equals("StillAlive"), "Server survived invalid frames");
        }
    }

    private static void framing(int port) throws Exception {
        try (Peer sender = new Peer(port, "Framer"); Peer receiver = new Peer(port, "Reader")) {
            sender.until("WELCOME"); receiver.until("WELCOME");
            String frame = Protocol.encode("SAY", "fragmented message") + "\n";
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            sender.sendRaw(Arrays.copyOfRange(bytes, 0, 5));
            sender.sendRaw(Arrays.copyOfRange(bytes, 5, bytes.length));
            check(field(receiver.until("ROOM_MSG"), 3, "ROOM_MSG").equals("fragmented message"), "Split TCP frame reconstructed");
            String joined = Protocol.encode("SAY", "first together") + "\n" +
                    Protocol.encode("SAY", "second together") + "\n";
            sender.sendRaw(joined.getBytes(StandardCharsets.UTF_8));
            check(field(receiver.until("ROOM_MSG"), 3, "ROOM_MSG").equals("first together"), "First coalesced frame decoded");
            check(field(receiver.until("ROOM_MSG"), 3, "ROOM_MSG").equals("second together"), "Second coalesced frame decoded");
        }
    }

    private static void concurrency(int port) throws Exception {
        final int perSender = 25;
        try (Peer a = new Peer(port, "ConcurrentA"); Peer b = new Peer(port, "ConcurrentB"); Peer observer = new Peer(port, "Observer")) {
            a.until("WELCOME"); b.until("WELCOME"); observer.until("WELCOME");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch begin = new CountDownLatch(1);
            try {
                Future<?> f1 = pool.submit(() -> { try { begin.await(); for (int i = 0; i < perSender; i++) a.send("SAY", "A-" + i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                Future<?> f2 = pool.submit(() -> { try { begin.await(); for (int i = 0; i < perSender; i++) b.send("SAY", "B-" + i); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                long started = System.nanoTime();
                begin.countDown();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < 2 * perSender; i++) {
                    String[] e = observer.until("ROOM_MSG");
                    seen.add(field(e, 3, "ROOM_MSG"));
                }
                f1.get(10, TimeUnit.SECONDS);
                f2.get(10, TimeUnit.SECONDS);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                check(seen.size() == 2 * perSender, "Concurrent sends: " + seen.size() + "/" + (2 * perSender) + " delivered to observer (" + elapsedMs + " ms)");
                check(seen.contains("A-0") && seen.contains("A-24") && seen.contains("B-0") && seen.contains("B-24"), "Both senders fully represented");
            } finally { pool.shutdownNow(); }
        }
    }

    private static void disconnect(int port) throws Exception {
        try (Peer observer = new Peer(port, "Watcher")) {
            observer.until("WELCOME");
            Peer shortLived = new Peer(port, "Vanishing");
            shortLived.until("WELCOME");
            shortLived.close();
            awaitUserGone(observer, "Vanishing");
            check(true, "Abrupt disconnect removed online user");
            try (Peer again = new Peer(port, "Vanishing")) {
                check(field(again.until("WELCOME"), 1, "WELCOME").equals("Vanishing"), "Username reusable after abrupt disconnect");
            }
        }
    }
}
