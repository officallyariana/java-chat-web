"""Local WebSocket load test for the browser-only Java Chat System.
Requires Python 3.10+, websockets 14+, psutil, JDK 17+ on PATH.
Run from the Java project root. Starts isolated Java processes; never uses live history.
"""
import argparse, asyncio, base64, csv, hashlib, hmac, os, secrets, socket, statistics, subprocess, tempfile, time
from pathlib import Path
from urllib.parse import urlencode
import psutil
from websockets.asyncio.client import connect

ORIGIN = 'http://127.0.0.1:8000'
def encode(*fields):
    return '\t'.join(base64.b64encode(x.encode()).decode() for x in fields)
def decode(frame):
    return [base64.b64decode(x).decode() for x in frame.split('\t')]
def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]
async def ready(proc, port):
    for _ in range(100):
        if proc.poll() is not None: raise RuntimeError('Java process exited; see benchmark logs')
        try:
            _, writer = await asyncio.open_connection('127.0.0.1', port)
            writer.close(); await writer.wait_closed(); return
        except OSError: await asyncio.sleep(.1)
    raise TimeoutError(f'Port {port} did not start')
async def trial(n, messages, root, logs):
    secret = secrets.token_urlsafe(32)
    tcp, ws = free_port(), free_port()
    while ws == tcp: ws = free_port()
    env = dict(os.environ, CHAT_WEB_SECRET=secret, CHAT_TCP_PORT=str(tcp), CHAT_WS_PORT=str(ws), CHAT_WEB_ORIGIN=ORIGIN)
    procs, peers, readers = [], [], []
    with tempfile.TemporaryDirectory(prefix='chat-load-') as data, (logs/f'{n}-server.log').open('w') as sl, (logs/f'{n}-bridge.log').open('w') as bl:
        try:
            procs.append(subprocess.Popen(['java','-cp',str(root/'out'),'ChatServer','--port',str(tcp),'--data',data], cwd=root, stdout=sl, stderr=subprocess.STDOUT))
            await ready(procs[0],tcp)
            procs.append(subprocess.Popen(['java','-cp',str(root/'out'),'WebSocketBridge'], cwd=root, env=env, stdout=bl, stderr=subprocess.STDOUT))
            await ready(procs[1],ws)
            stats = [psutil.Process(p.pid) for p in procs]
            def sample(): return sum(p.num_threads() for p in stats),sum(p.memory_info().rss for p in stats)/1048576
            baseline_threads, baseline_rss = sample()
            received = set(); acks = {}; starts = {}; changed = asyncio.Event()
            async def reader(i, peer):
                async for frame in peer:
                    f = decode(frame)
                    if f[0]=='ROOM_MSG' and f[3].startswith('bench-'):
                        received.add((i,f[3])); changed.set()
                    elif f[0]=='ACK' and f[1] in starts and f[2:] == ['SAY','STORED']:
                        if starts[f[1]][0]==i: acks[f[1]]=(time.perf_counter()-starts[f[1]][1])*1000; changed.set()
                    elif f[0]=='ERROR': raise RuntimeError(f'Server error: {f}')
            for i in range(n):
                name=f'Load{i}'; ts=str(int(time.time())); sig=hmac.new(secret.encode(),f'{name}\n{ts}'.encode(),hashlib.sha256).hexdigest()
                peer=await connect(f'ws://127.0.0.1:{ws}/chat?'+urlencode(dict(name=name,ts=ts,sig=sig)),origin=ORIGIN,open_timeout=30,close_timeout=1,ping_interval=None,max_queue=None)
                peers.append(peer)
                welcome=decode(await asyncio.wait_for(peer.recv(),5))
                if welcome[0]!='WELCOME': raise RuntimeError(str(welcome))
                readers.append(asyncio.create_task(reader(i,peer)))
            await asyncio.sleep(.3)
            active_threads, active_rss=sample()
            peak_rss=active_rss
            for m in range(messages):
                i=m%n; mid=str(m+1); text=f'bench-{m+1}'
                starts[mid]=(i,time.perf_counter()); await peers[i].send(encode('SAY',mid,text))
                deadline=time.monotonic()+5
                while mid not in acks or any((j,text) not in received for j in range(n)):
                    changed.clear()
                    remaining=deadline-time.monotonic()
                    if remaining<=0: break
                    try: await asyncio.wait_for(changed.wait(),remaining)
                    except asyncio.TimeoutError: break
                peak_rss=max(peak_rss,sample()[1])
            for task in readers:
                if task.done(): task.result()
            row=dict(clients=n,expected_client_threads=3*n,baseline_os_threads=baseline_threads,active_os_threads=active_threads,baseline_rss_mib=round(baseline_rss,2),active_rss_mib=round(active_rss,2),sampled_peak_rss_mib=round(peak_rss,2),messages_sent=messages,expected_deliveries=n*messages,observed_deliveries=len(received),delivery_pct=round(100*len(received)/(n*messages),2),acks=len(acks),mean_ack_ms=round(statistics.mean(acks.values()),3) if acks else '',max_ack_ms=round(max(acks.values()),3) if acks else '')
            return row
        finally:
            await asyncio.gather(*(p.close() for p in peers),return_exceptions=True)
            for task in readers: task.cancel()
            await asyncio.gather(*readers,return_exceptions=True)
            for proc in reversed(procs):
                if proc.poll() is None: proc.terminate()
                try: proc.wait(timeout=5)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
async def main():
    ap=argparse.ArgumentParser(description=__doc__); ap.add_argument('--clients',nargs='+',type=int,default=[3,10,25,50,100]); ap.add_argument('--messages',type=int,default=10); ap.add_argument('--output',type=Path,default=Path('benchmark-results')); args=ap.parse_args()
    if any(n<1 or n>200 for n in args.clients) or not 1<=args.messages<=100: ap.error('Use 1–200 clients and 1–100 messages per trial')
    root=Path.cwd(); logs=args.output.resolve(); logs.mkdir(parents=True,exist_ok=True)
    if not (root/'out'/'WebSocketBridge.class').exists(): ap.error('Compile Java sources into out first')
    with (logs/'results.csv').open('w',newline='') as file:
        writer=None
        for n in args.clients:
            row=await trial(n,args.messages,root,logs)
            if writer is None: writer=csv.DictWriter(file,fieldnames=row); writer.writeheader()
            writer.writerow(row); file.flush(); print(row,flush=True)
if __name__=='__main__': asyncio.run(main())
