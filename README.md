# Enhanced Java Chat System — Browser Edition (No Swing)

A **web-based, multi-user chat system** built with **Java, PHP, HTML, CSS, and JavaScript**. Users interact exclusively through a browser; there are **no Java Swing components and no desktop client required**.

## Features

- Multiple simultaneous users on one Java TCP server
- Chat rooms, room-isolated messages, private messages, and online-user list
- Saved room/DM history, reconnections, server acknowledgments and timing
- Browser chat interface built with HTML, CSS and JavaScript
- PHP session-based username selection and short-lived signed connection tickets
- Java WebSocket bridge that forwards browser messages to the existing Java TCP server
- Java integration tests for protocol handling, validation, concurrency, and failures

## Requirements

- **JDK 17+** (`java -version` and `javac -version`)
- **PHP 8+** (`php -v`); install PHP or use the PHP binary supplied with a suitable local setup
- A modern browser (Chrome, Edge, Firefox)
- Windows PowerShell for the commands below (or equivalent shell on other systems)
- No Swing, JavaFX, npm, Composer, or external Java libraries

## Download

Download and extract the Web Edition ZIP, then open PowerShell **in the extracted folder** (the folder containing `src` and `web`). If this version has been pushed to GitHub, classmates can instead download the repository ZIP or clone it. Ensure the GitHub repository has the **latest Web Edition** first.

## Start on Windows (three PowerShell terminals)

Keep all three terminals open. Use **the same secret** for the bridge and PHP. Generate a random secret in PowerShell once (for example, using `[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')`) and paste the value in both commands. Never commit it to GitHub.

**Terminal 1 — Java chat server**

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
java -cp out ChatServer
```

**Terminal 2 — Java WebSocket bridge**

```powershell
$env:CHAT_WEB_SECRET = 'PASTE_YOUR_64_CHARACTER_SECRET_HERE'
java -cp out WebSocketBridge
```

**Terminal 3 — PHP web server**

```powershell
$env:CHAT_WEB_SECRET = 'PASTE_YOUR_64_CHARACTER_SECRET_HERE'
php -S 127.0.0.1:8000 -t web
```

Open **http://127.0.0.1:8000** in your browser. Use that exact host (not `localhost`) because the WebSocket bridge checks the browser's origin. Choose a username to enter chat.

**Test multiple users on one computer:** Open a normal browser window as `Alice` and an Incognito/InPrivate window as `Bob`. For a third user, open a separate browser profile or a different browser as `Charlie`. Multiple tabs in one browser profile usually share the same PHP session/username.

## How to use the website

1. Choose a unique username when the site opens.
2. Click the `+` button next to **Rooms** to join or create a room, such as `project`.
3. Send messages with the message field and **Send** button.
4. Click a user under **Online users** to start a private conversation.
5. Click **Room chat** to return to the shared room.
6. Click **Load history** to view earlier messages.
7. Observe server acknowledgments and response timing when sending messages.
8. Use **Leave account** to exit the current browser session.

## How classmates can test independently

Every classmate can extract the same project and start all three services **on their own computer**. They can open two or three separate browser sessions to test the full application without connecting to your PC.

For **shared local testing**, the current web setup is intentionally limited to `127.0.0.1` and is **not accessible from other computers**. To run one shared remote website, you must first implement and deploy HTTPS, WSS, real accounts and authorization, a production PHP server, and a secure reverse proxy. **Do not open ports 5555, 8081, or 8000 directly to the internet.**

## Run the automated tests

After compiling, open a fourth terminal in the same folder:

```powershell
java -cp out ChatIntegrationTest
```

The test suite starts a temporary Java chat server on a separate port; it does not need the manually started server. In the previously prepared edition, the suite was designed to produce `PASS: 36 integration assertions.`; verify the actual result on your machine rather than assuming it passed.

### Suggested class demo checklist

- [ ] Connect Alice, Bob, and Charlie from separate browser sessions
- [ ] Show usernames cannot be duplicated while connected
- [ ] Create a `project` room; verify lobby users do not get its messages
- [ ] Send a private message
- [ ] Refresh/reconnect, and load previous history
- [ ] Send messages from two users simultaneously
- [ ] Disconnect a client and show the server remains running
- [ ] Explain TCP newline framing, Base64 fields, multithreading, WebSocket bridge, and acknowledgments
- [ ] Run integration tests and record real results

## Architecture

```text
Browsers (HTML + CSS + JavaScript)
           | HTTP to PHP (login page + signed ticket)
           | WebSocket ws://127.0.0.1:8081 (live messages)
           v
Java WebSocketBridge.java (ticket/origin validation)
           | One persistent local TCP connection per user
           v
Java ChatServer.java (TCP port 5555, concurrency, routing)
           |
           v
Java HistoryStore.java (saved chat messages)
```

PHP serves the site and issues short-lived signed tickets. JavaScript opens the persistent WebSocket. The Java bridge connects to the TCP server on each browser user's behalf. The server processes usernames, rooms, private messages, and history. **PHP does not directly hold the persistent chat socket.**

The internal Java protocol consists of newline-terminated messages with UTF-8 fields encoded in Base64 and separated by TAB characters. The bridge forwards this protocol through WebSocket text frames.

## Project structure

```text
src/
  ChatServer.java          Java multithreaded TCP server
  WebSocketBridge.java     Java WebSocket-to-TCP bridge
  Protocol.java            Message encoding and decoding
  HistoryStore.java        Room and private message persistence
  ChatIntegrationTest.java Automated network tests
web/
  index.php                PHP login, session, signed ticket and HTML page
  assets/style.css         Browser styles
  assets/chat.js           Browser client logic
README.md                  Setup and classmates' testing guide
.gitignore                 Ignore compiled code and chat data
```

**No `ChatGUI.java`, `ChatClient.java`, Java Swing, or desktop GUI is needed.**

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `javac` invalid filename error | Run the PowerShell `Get-ChildItem` compilation command from the folder containing `src`. |
| `java` or `javac` not recognized | Install JDK 17+ and reopen PowerShell. |
| `php` not recognized | Install PHP 8+ and make `php.exe` available on PATH. |
| Website shows `CHAT_WEB_SECRET` missing | Set the secret **before** starting PHP, and use the same value for the Java bridge. |
| Browser cannot connect | Verify all three services are running and use `http://127.0.0.1:8000`. |
| WebSocket 403 | Check matching secrets, exact origin, and refresh the page for a fresh ticket. |
| Username already online | Use a different name or close the other session and allow its socket to disconnect. |
| Port already in use | Stop the previous Java/PHP process or change the relevant port and matching settings. |

## Security limitations

This is a **local classroom prototype**, not a public user-account service. Users choose usernames without passwords; shared PHP sessions and signed tickets do not authenticate real identities. An offline username may be impersonated, potentially exposing stored conversations. Message history is stored in local files. Do not use it for sensitive chats or expose the demo services directly to the public internet. Hosting for remote users needs HTTPS/WSS, real sign-in, access controls, and production hardening.

## Publish this update to GitHub

After copying these browser-edition files into your existing repository, remove the old desktop client files from that repo and commit the changes:

```powershell
git rm src/ChatGUI.java src/ChatClient.java
git add README.md src web .gitignore
git commit -m "Switch Java chat project to browser-only interface"
git push origin main
```

If `git rm` reports a file is not tracked, remove it in File Explorer and use `git add -A` instead. Keep private secrets and stored chat data out of Git.
