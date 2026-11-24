<?php
declare(strict_types=1);
header('Content-Security-Policy: default-src \'self\'; connect-src \'self\' ws://127.0.0.1:8081; style-src \'self\'; script-src \'self\'; base-uri \'none\'; frame-ancestors \'none\'');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store');
session_start();
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $name = trim((string)($_POST['username'] ?? ''));
    if (preg_match('/^[A-Za-z0-9_-]{1,20}$/D', $name)) {
        $_SESSION['chat_name'] = $name;
        header('Location: /'); exit;
    }
    $error = 'Use 1–20 letters, numbers, underscores or hyphens.';
}
if (isset($_GET['logout'])) { unset($_SESSION['chat_name']); header('Location: /'); exit; }
$name = (string)($_SESSION['chat_name'] ?? '');
$secret = getenv('CHAT_WEB_SECRET') ?: '';
if (strlen($secret) < 32) { http_response_code(503); exit('Server setup incomplete: CHAT_WEB_SECRET is missing. See README.'); }
$ts = (string)time();
$sig = hash_hmac('sha256', $name . "\n" . $ts, $secret);
$config = ['name' => $name, 'ws' => 'ws://127.0.0.1:8081/chat?'.http_build_query(['name'=>$name,'ts'=>$ts,'sig'=>$sig])];
?>
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NorthBridge Java Chat | Web Client</title><link rel="stylesheet" href="/assets/style.css"></head>
<body>
<?php if ($name === ''): ?>
<main class="login"><div class="brand">◎ Java Chat <span>WEB</span></div><h1>Join the conversation</h1><p>Connect to the existing Java TCP chat server through your browser.</p><form method="post"><label for="username">Choose a username</label><input id="username" name="username" pattern="[A-Za-z0-9_-]{1,20}" minlength="1" maxlength="20" autofocus required placeholder="e.g. Alice"><button type="submit">Enter chat</button></form><?php if(isset($error)): ?><p class="error"><?=htmlspecialchars($error, ENT_QUOTES)?></p><?php endif; ?><small>For local classroom testing only. This demo does not provide password-based accounts.</small></main>
<?php else: ?>
<div class="shell">
    <aside class="side">
        <div class="brand">◎ Java Chat <span>WEB</span>
    </div>
    <div class="side-title">
        <b>Rooms</b>
        <button type="button" id="new-room" title="Join or create room">＋</button>
    </div>
    <div id="rooms" class="navlist">
    </div>
    <div class="side-title">
        <b>Online users</b>
        <button type="button" id="refresh" title="Refresh users and rooms">↻</button>
    </div>
    <div id="users" class="navlist">
    </div>
    <div class="side-footer">
        <span id="connection-dot" class="dot">
        </span>
        <span id="connection">Connecting…</span>
        <a href="/?logout=1">Leave account</a>
    </div>
</aside>
<main class="chat">
    <header>
        <div>
            <small>ENHANCED JAVA CHAT</small>
            <h1 id="channel"># lobby</h1>
        </div>
        <button type="button" id="room-chat">Room chat</button>
    </header>
    <div id="messages" class="messages" role="log" aria-live="polite">
    </div>
    <div class="actions">
        <button type="button" id="history">Load history</button>
        <span id="performance">Awaiting connection</span>
    </div>
    <form id="composer">
        <input id="message" autocomplete="off" maxlength="2000" placeholder="Write a message…" disabled>
        <button id="send" type="submit" disabled>Send</button>
    </form>
</main>
</div>
<script id="chat-config" type="application/json"><?=json_encode($config, JSON_HEX_TAG|JSON_HEX_AMP|JSON_HEX_APOS|JSON_HEX_QUOT|JSON_UNESCAPED_SLASHES)?></script>
<script src="/assets/chat.js" defer></script>
<?php endif; ?>
</body>
</html>
