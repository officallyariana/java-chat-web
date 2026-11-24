# Enhanced Java Chat System

A web-based, multi-user chat application built with Java, PHP, HTML, CSS, and JavaScript. Users interact exclusively through a modern web browser. The system consists of three cooperating services: a Java TCP chat server, a Java WebSocket-to-TCP bridge, and a PHP web frontend.

## Features

- **Multi-user chat** — multiple simultaneous users on a single Java TCP server
- **Chat rooms** — create and join rooms with message isolation between rooms
- **Private messaging** — direct messages between online users
- **Online-user list** — see who is currently connected
- **Message history** — persisted room and direct-message history across reconnections
- **Server acknowledgments** — optional request IDs with `ACK` responses for `SAY` and `DM`
- **Browser interface** — HTML/CSS/JavaScript client served by PHP
- **Signed connection tickets** — PHP-issued, HMAC-authenticated short-lived tokens for WebSocket access
- **Integration test suite** — automated network tests covering protocol, validation, concurrency, and failure handling

## Table of Contents

- [Requirements](#requirements)
- [Project Structure](#project-structure)
- [Architecture](#architecture)
- [Installation](#installation)
- [Configuration](#configuration)
- [Quick Start](#quick-start)
- [Using the Application](#using-the-application)
- [Running the Tests](#running-the-tests)
- [Contributing](#contributing)
- [Security](#security)
- [Troubleshooting](#troubleshooting)

## Requirements

| Dependency | Minimum Version | Purpose |
|------------|-----------------|---------|
| JDK | 17+ | Compile and run the Java chat server and WebSocket bridge |
| PHP | 8+ | Serve the web frontend and issue signed connection tickets |
| Web browser | Chrome, Edge, Firefox, or equivalent | Chat client |
| Terminal | PowerShell (Windows), Bash, or Zsh | Run service startup commands |


## Project Structure

```text
java-chat-web/
├── src/
│   ├── ChatServer.java          # Multithreaded TCP chat server (port 5555)
│   ├── WebSocketBridge.java     # WebSocket-to-TCP bridge (port 8081)
│   ├── Protocol.java            # Base64/TAB message encoding and decoding
│   ├── HistoryStore.java        # Room and direct-message persistence
│   └── ChatIntegrationTest.java # Automated integration tests
├── web/
│   ├── index.php                # PHP login, session, signed ticket, and HTML page
│   ├── assets/
│   │   ├── style.css            # Browser styles
│   │   └── chat.js              # Browser client logic
│   └── ...
├── data/                        # Runtime chat history files (created at startup)
├── out/                         # Compiled Java class output (gitignored)
├── .gitignore                   # Git ignore rules
└── README.md                    # This file
```

## Architecture

```text
Browsers (HTML + CSS + JavaScript)
           | HTTP to PHP (login + signed ticket)
           | WebSocket ws://127.0.0.1:8081 (live messages)
           v
Java WebSocketBridge.java (ticket/origin validation)
           | One persistent local TCP connection per user
           v
Java ChatServer.java (TCP port 5555, concurrency, routing)
           |
           v
Java HistoryStore.java (saved room and DM messages)
```

PHP serves the site and issues short-lived, HMAC-signed connection tickets. JavaScript opens a persistent WebSocket to the Java bridge, which forwards messages to the TCP chat server on each browser user's behalf. The server processes usernames, rooms, private messages, and history. PHP does not hold any persistent chat sockets.

## Installation

### 1. Clone the repository

```bash
git clone 
cd java-chat-web
```

### 2. Install dependencies

**JDK 17+** (Windows, macOS, or Linux):

- your platform's package manager
- Verify: `java -version` and `javac -version`

**PHP 8+**:

- Windows: download php then — add `php.exe` to PATH
- macOS: `brew install php@8.2`
- Linux (Debian/Ubuntu): `sudo apt-get install php8.2-cli`

**Web browser**: Chrome,safari etc.

### 3. Compile the Java sources

From the repository root:

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem .\src\*.java).FullName
```

Equivalent Bash:

```bash
javac -encoding UTF-8 -d out src/*.java
```

Compiled classes are written to the `out/` directory.

## Configuration

All configuration is via environment variables. Generate a single strong secret and reuse it across all three services:

```powershell
$secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
```

| Variable | Default | Description |
|----------|---------|-------------|
| `CHAT_WEB_SECRET` | *(required)* | Shared secret (32+ characters) used by PHP and the Java bridge to validate signed tickets |
| `CHAT_WS_PORT` | `8081` | Port for the WebSocket bridge |
| `CHAT_TCP_PORT` | `5555` | Port for the Java chat TCP server |
| `CHAT_WEB_ORIGIN` | `http://127.0.0.1:8000` | Expected browser `Origin` header for WebSocket connections |

**Never commit `CHAT_WEB_SECRET` to version control.** Add it to your shell profile or set it per terminal session.

## Quick Start

Open **three terminals** in the repository root. Use the same `CHAT_WEB_SECRET` in all three.

### Terminal 1 — Java chat server

```powershell
java -cp out ChatServer
```

### Terminal 2 — Java WebSocket bridge

```powershell
$env:CHAT_WEB_SECRET = '<your-shared-secret>'
java -cp out WebSocketBridge
```

### Terminal 3 — PHP web server

```powershell
$env:CHAT_WEB_SECRET = '<your-shared-secret>'
php -S 127.0.0.1:8000 -t web
```

### Open the application

Navigate to **http://127.0.0.1:8000** in your browser. Use `127.0.0.1` exactly (not `localhost`) because the bridge validates the `Origin` header. Choose a username to enter the chat.

### Multiple users on one computer

- User 1: normal browser window as `Alice`
- User 2: Incognito/InPrivate window as `Bob`
- User 3: separate browser profile or different browser as `Charlie`

Tabs in the same browser profile usually share the same PHP session and username.

## Using the Application

1. Choose a unique username when the site opens.
2. Click the `+` button next to **Rooms** to join or create a room (e.g., `project`).
3. Type a message and click **Send** (or press Enter).
4. Click a user under **Online users** to start a private conversation.
5. Click **Room chat** to return to the shared room.
6. Click **Load history** to view earlier room and direct messages.
7. Watch server acknowledgments and response timings appear in the UI.
8. Click **Leave account** to end your session.

## Running the Tests

The integration test suite starts a temporary Java chat server on an ephemeral port and does not require the manually started services.

```powershell
java -cp out ChatIntegrationTest
```

Expected output ends with:

```
PASS: 36 integration assertions.
```

The suite covers:
- Connection, duplicate usernames, and reconnection
- Room creation, joining, and isolation
- Room and direct messaging with acknowledgments
- Message validation (blank, oversized, invalid IDs, malformed Base64, malformed UTF-8)
- TCP frame splitting and coalescing
- Concurrent messaging from multiple clients
- Abrupt disconnects and username reuse

## Contributing

We welcome contributions. Please follow these guidelines:

### 1. Fork and branch

Fork the repository and create a feature branch from `main`:

```bash
git checkout -b feature/your-feature-name
```

### 2. Make your changes

- Follow the existing code style (4-space indentation, braces on the same line, Javadoc on public API)
- Do not commit secrets, keys, or credentials
- Keep changes focused on a single concern

### 3. Compile and test

```powershell
javac -encoding UTF-8 -d out src/*.java
java -cp out ChatIntegrationTest
```

All 36 integration assertions must pass.

### 4. Submit a pull request

- Provide a clear description of the change and why it is needed
- Reference any related issues
- Ensure the PR title follows conventional commits style (e.g., `feat:`, `fix:`, `docs:`)

### Code review expectations

Reviewers will check for:
- Correctness under concurrent access
- Input validation and error handling
- No secrets or credentials in code
- Consistent formatting and naming

## Security

- The application binds to `127.0.0.1` only and is **not intended for direct internet exposure**.
- Do **not** open ports 5555, 8081, or 8000 to the internet.
- For remote/shared deployment, implement HTTPS, WSS, real user accounts, authorization, and a secure reverse proxy first.
- `CHAT_WEB_SECRET` must be at least 32 random characters and must match between PHP and the Java bridge.
- Never commit `CHAT_WEB_SECRET` or other credentials to GitHub.

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|--------------|-----|
| `javac` or `java` not recognized | JDK not installed or not on PATH | Install JDK 17+ and restart your terminal |
| `php` not recognized | PHP not installed or not on PATH | Install PHP 8+ and add it to PATH |
| `CHAT_WEB_SECRET` missing | Secret not set before starting PHP or bridge | Set `$env:CHAT_WEB_SECRET` in each terminal before launching |
| Browser cannot connect | One or more services not running | Verify all three terminals are active |
| WebSocket 403 | Origin mismatch or missing secret | Use `http://127.0.0.1:8000` exactly; confirm secrets match |
| Username already online | Previous session still connected | Close the other browser/tab and wait for socket cleanup |
| Port already in use | Another process holds the port | Stop the existing process or change the port via environment variables |
| Tests fail with fewer than 36 assertions | Environment timing or port conflict | Re-run; if persistent, verify no other chat server is running |


