import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebSocketBridge {
    private static final int LIMIT = 16384;
    private final String secret, origin;
    private final int port, backendPort;
    private final ExecutorService clients = Executors.newCachedThreadPool();

    private WebSocketBridge(int port, int backendPort, String secret, String origin) {
        this.port = port; this.backendPort = backendPort; this.secret = secret; this.origin = origin;
    }

    public static void main(String[] args) throws Exception {
        String secret = System.getenv("CHAT_WEB_SECRET");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Set CHAT_WEB_SECRET (32+ random characters) on PHP and Java processes");
        int port = Integer.parseInt(System.getenv().getOrDefault("CHAT_WS_PORT", "8081"));
        int backend = Integer.parseInt(System.getenv().getOrDefault("CHAT_TCP_PORT", "5555"));
        String origin = System.getenv().getOrDefault("CHAT_WEB_ORIGIN", "http://127.0.0.1:8000");
        new WebSocketBridge(port, backend, secret, origin).start();
    }

    private void start() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            System.out.printf("WebSocket bridge listening on localhost:%d (origin %s)%n", port, origin);
            while (true) {
                Socket peer = server.accept();
                clients.execute(() -> handle(peer));
            }
        }
    }

    private void handle(Socket ws) {
        Socket tcp = null;
        try (ws) {
            ws.setSoTimeout(10000);
            InputStream input = ws.getInputStream();
            OutputStream output = ws.getOutputStream();
            String request = readHeaders(input);
            String[] lines = request.split("\\r\\n");
            if (lines.length == 0 || !lines[0].startsWith("GET /chat?")) { reject(output, 404); return; }
            Map<String, String> hdr = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) hdr.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            if (!origin.equals(hdr.get("origin")) || !"websocket".equalsIgnoreCase(hdr.get("upgrade")) ||
                !hdr.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade") ||
                !"13".equals(hdr.get("sec-websocket-version"))) { reject(output, 403); return; }
            String key = hdr.get("sec-websocket-key");
            if (key == null || Base64.getDecoder().decode(key).length != 16) { reject(output, 400); return; }
            String query = lines[0].split(" ")[1].substring("/chat?".length());
            Map<String, String> params = new HashMap<>();
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            String name = params.getOrDefault("name", "");
            String ts = params.getOrDefault("ts", "");
            String sig = params.getOrDefault("sig", "");
            if (!name.matches("[A-Za-z0-9_-]{1,20}") || !validSignature(name, ts, sig)) { reject(output, 403); return; }

            tcp = new Socket();
            tcp.connect(new InetSocketAddress("127.0.0.1", backendPort), 2500);
            Socket backend = tcp;
            BufferedReader serverIn = new BufferedReader(new InputStreamReader(backend.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter serverOut = new PrintWriter(new OutputStreamWriter(backend.getOutputStream(), StandardCharsets.UTF_8), true);
            serverOut.println(Protocol.encode("HELLO", name));
            backend.setSoTimeout(5000);
            String welcome = serverIn.readLine();
            if (welcome == null) { reject(output, 503); return; }
            backend.setSoTimeout(0);
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            ws.setSoTimeout(0);
            Object lock = new Object();
            writeFrame(output, lock, 1, welcome.getBytes(StandardCharsets.UTF_8));
            if (!"WELCOME".equals(Protocol.decode(welcome)[0])) return;
            Thread fromServer = new Thread(() -> {
                try {
                    String line;
                    while ((line = serverIn.readLine()) != null) {
                        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > LIMIT) break;
                        writeFrame(output, lock, 1, bytes);
                    }
                } catch (IOException ignored) { }
                finally { try { ws.close(); } catch (IOException ignored) { } }
            }, "java-chat-to-web");
            fromServer.setDaemon(true);
            fromServer.start();
            while (true) {
                Frame f = readFrame(input);
                if (f.opcode == 8) break;
                if (f.opcode == 9) { writeFrame(output, lock, 10, f.payload); continue; }
                if (f.opcode == 10) continue;
                if (f.opcode != 1) break;
                String line = strictUtf8(f.payload);
                if (line.indexOf('\n') != -1 || line.indexOf('\r') != -1 || line.getBytes(StandardCharsets.UTF_8).length > LIMIT) break;
                String[] decoded = Protocol.decode(line);
                if (decoded.length == 0 || "HELLO".equals(decoded[0])) break;
                serverOut.println(line);
                if (serverOut.checkError()) break;
            }
        } catch (Exception e) {
            System.err.println("Bridge connection closed: " + e.getClass().getSimpleName());
        } finally {
            if (tcp != null) try { tcp.close(); } catch (IOException ignored) { }
        }
    }

    private boolean validSignature(String name, String timestamp, String sig) throws Exception {
        long t;
        try { t = Long.parseLong(timestamp); } catch (NumberFormatException e) { return false; }
        if (Math.abs(Instant.now().getEpochSecond() - t) > 300 || !sig.matches("[0-9a-f]{64}")) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder expected = new StringBuilder();
        for (byte b : mac.doFinal((name + "\n" + timestamp).getBytes(StandardCharsets.UTF_8))) expected.append(String.format("%02x", b & 255));
        return MessageDigest.isEqual(expected.toString().getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }
    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > 8192) throw new IOException("Header too large");
            byte[] a = b.toByteArray(); int n = a.length;
            if (n >= 4 && a[n-4] == 13 && a[n-3] == 10 && a[n-2] == 13 && a[n-1] == 10) return b.toString(StandardCharsets.US_ASCII);
        }
        throw new EOFException("Incomplete request");
    }
    private static void reject(OutputStream out, int status) throws IOException {
        String reason = switch(status) { case 400 -> "Bad Request"; case 403 -> "Forbidden"; case 404 -> "Not Found"; default -> "Service Unavailable"; };
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
    }
    private record Frame(int opcode, byte[] payload) {}
    private static Frame readFrame(InputStream in) throws IOException {
        int a = in.read(), b = in.read();
        if (a < 0 || b < 0) throw new EOFException();
        if ((a & 0x80) == 0 || (a & 0x70) != 0 || (b & 0x80) == 0) throw new IOException("Unsupported frame");
        int op = a & 15;
        long len = b & 127;
        if (len == 126) len = ((long) readByte(in) << 8) | readByte(in);
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len > LIMIT || ((op == 8 || op == 9 || op == 10) && len > 125)) throw new IOException("Frame too large");
        byte[] mask = in.readNBytes(4);
        if (mask.length != 4) throw new EOFException();
        byte[] p = in.readNBytes((int)len);
        if (p.length != len) throw new EOFException();
        for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
        return new Frame(op, p);
    }
    private static int readByte(InputStream in) throws IOException { int n = in.read(); if (n == -1) throw new EOFException(); return n; }
    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void writeFrame(OutputStream out, Object lock, int op, byte[] payload) throws IOException {
        synchronized (lock) {
            out.write(0x80 | op);
            if (payload.length <= 125) out.write(payload.length);
            else { out.write(126); out.write((payload.length >>> 8) & 255); out.write(payload.length & 255); }
            out.write(payload); out.flush();
        }
    }
}
