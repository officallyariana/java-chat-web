# Enhanced Java Chat System — Web + Desktop Edition

A working Java TCP chat server with **two types of client**: a browser client built with **HTML/CSS/JavaScript/PHP**, and the existing **Java Swing / terminal clients**.

> **Security / scope:** This is a **localhost-only classroom demonstration**. The PHP page issues short-lived, signed WebSocket tickets, but it has no passwords or individual authenticated accounts. The server's messages are plain TCP on loopback and the browser uses `ws://` locally. **Do not open ports 5555, 8081, or 8000 to the public internet.** Public deployment requires HTTPS + WSS, authenticated accounts and per-user authorization, and hardened/rate-limited infrastructure.

## Requirements

- Java JDK 17+ (recommended 21)
- PHP 8+ (PHP CLI)
- Windows PowerShell (commands below) or Linux/macOS shell
- A modern browser
- No Composer, npm, Java dependencies, database server, or frameworks required.

## Download / clone

```powershell
 git clone 
 cd java-chat-web
```

If you downloaded this *web edition* as a ZIP, extract it and run the following commands in that folder. If you've not copied the web edition into GitHub yet, cloning the existing repository may **not** include the web folder or WebSocketBridge.java.

## Start the web edition (Windows PowerShell)

Open **four PowerShell terminals** in the project root.

**Important:** To use the default local web setup, visit **http://127.0.0.1:8000** exactly (not `localhost`) because the bridge checks the browser Origin.

Choose **one shared secret** and use the exact same value in Terminal 2 and Terminal 3. The example below is for local demos only; generate a random value for your own machine and do not commit it.

### Terminal 1 — compile and run the Java chat server

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
java -cp out ChatServer
```

### Terminal 2 — run the Java WebSocket bridge

```powershell
$env:CHAT_WEB_SECRET = 'replace-with-a-private-random-string-at-least-32-characters'
java -cp out WebSocketBridge
```

### Terminal 3 — run the PHP website

```powershell
$env:CHAT_WEB_SECRET = 'replace-with-a-private-random-string-at-least-32-characters'
php -S 127.0.0.1:8000 -t web
```

### Terminal 4 — test the browser (or open it manually)

```powershell
Start-Process 'http://127.0.0.1:8000'
```

Enter `Alice` as the username. Open an **incognito/private browser window** at the same URL and enter `Bob`. Different browser sessions matter because the site uses a PHP session to remember the username. You can alternatively launch a Java GUI user:

```powershell
java -cp out ChatGUI
```

Set host `127.0.0.1`, port `5555`, username `Charlie`. Browser and GUI users can exchange messages **through the same Java TCP server**.

## Architecture

```text
Browser (HTML, CSS, JavaScript)
     | PHP serves page and signs short-lived tickets
     | WebSocket ws://127.0.0.1:8081 (persistent)
     v
Java WebSocketBridge.java (validates signed ticket + browser origin)
     | localhost TCP connection, one per browser user
     v
Java ChatServer.java (TCP 5555; rooms, usernames, DMs, history)
     |           ^
     v           | Java ChatGUI.java / ChatClient.java (TCP clients)
HistoryStore.java
```

**Transport / protocol:** Each existing TCP message is one newline-terminated line. Its fields are UTF-8 converted to Base64, joined by TAB characters. The browser sends the **same encoded messages** inside WebSocket text frames. The bridge authenticates the browser ticket, injects `HELLO username`, then forwards frames in both directions. WebSocket masking, handshakes and ping/pong are handled by `WebSocketBridge.java`.

**Roles:** Java handles server networking, presence, routing and persistence. PHP serves the login page and produces a short-lived HMAC ticket for the bridge. HTML/CSS renders the interface, and JavaScript runs the interactive browser client. PHP is **not** an active, persistent message router.

## Common problems

| Symptom | Fix |
| --- | --- |
| `javac` says invalid filename | Run the PowerShell command above **from the project root**, not `src/*.java` by itself. |
| Browser shows server setup incomplete | `CHAT_WEB_SECRET` must be set in the **PHP** terminal before launching PHP. |
| Browser says could not connect | Launch `ChatServer`, then `WebSocketBridge`, then PHP. Verify all terminals remain running. |
| WebSocket HTTP 403 | Visit `http://127.0.0.1:8000`, ensure **same secret** in both processes, refresh page to get a new 5-minute ticket. |
| Error: username already online | Disconnect another browser/GUI using the same name, or choose a different name. |
| PHP not recognized | Install PHP 8+ and add its directory to PATH, then reopen PowerShell. |
| Failed to bind port | Close other applications using 5555, 8081, or 8000. |

## Source layout

```text
src/
  ChatServer.java        Original Java chat server (enhanced version)
  ChatClient.java        Terminal chat client
  ChatGUI.java           Desktop Swing chat client
  Protocol.java          Existing Base64/tab protocol
  HistoryStore.java      Persistent messages on disk
  ChatIntegrationTest.java   TCP integration tests
  WebSocketBridge.java   NEW persistent browser-to-TCP gateway
web/
  index.php              PHP page + browser session + ticket
  assets/chat.js         Browser chat behavior
  assets/style.css       Responsive UI
```

## For the course demonstration

Explain why TCP was chosen, why newline framing is necessary with stream sockets, how the server handles multiple clients, how history survives reconnection, and why the bridge keeps a **separate persistent Java socket per web user**. Show duplicate name rejection, room isolation, private messages, multiple simultaneous users, unexpected disconnection, and results from `ChatIntegrationTest`.

## Security notes and limitations

- **Not a public login system:** usernames are self-chosen. Signed tickets bind a requested username to the PHP backend for a short time, but do not verify a real person's identity; other users may impersonate an offline name.
- **No public hosting as-is:** the WebSocket bridge binds only to loopback, and the PHP development server must also bind to loopback. Never expose the demo over port forwarding or open cloud firewall rules for it.
- HMAC signatures expire after five minutes and are checked with a constant-time comparison. Tickets in WebSocket URL query strings can appear in server logs. Don't publish them.
- No end-to-end encryption or persistent user accounts. Server-side chat histories are unencrypted files under `data/` and must not be committed.
- For a real remote deployment, use a production PHP web server with HTTPS, a WSS reverse proxy to the loopback WebSocket bridge, authenticated accounts, least-privilege processes, and stronger per-user access checks for history.
