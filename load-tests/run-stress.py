# Step stress: one JMeter run per thread count, each against a freshly reset app. Prints a table and
# writes results/stress-summary.txt. Usage: python run-stress.py 50 100 200 400 800
import csv, json, subprocess, sys, time, urllib.parse, urllib.request, os
steps = [int(x) for x in sys.argv[1:]] or [50, 100, 200, 400, 800]
RAMP, DUR = 20, 110
here = os.path.dirname(os.path.abspath(__file__))
work = here

def prom(q):
    url = 'http://localhost:19090/api/v1/query?' + urllib.parse.urlencode({'query': q})
    try:
        r = json.load(urllib.request.urlopen(url, timeout=10))['data']['result']
    except Exception:
        return float('nan')  # Prometheus (or Docker) is gone; keep the JMeter numbers
    return float(r[0]['value'][1]) if r else float('nan')

def pct(v, p):
    v = sorted(v); return v[min(len(v) - 1, int(len(v) * p))] if v else float('nan')

lines = []
for n in steps:
    subprocess.run(['sh', 'reset.sh'], cwd=here, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(10)  # let Prometheus scrape the restarted app
    jtl = os.path.join(here, 'results', f'stress-{n}.jtl')
    if os.path.exists(jtl): os.remove(jtl)
    t0 = time.time()
    subprocess.run(['docker', 'run', '--rm', '-e', 'JVM_ARGS=-Xms1g -Xmx3g', '--network', 'sos-loadtest_default', '-v', f'{work}:/work', '-v', f'{work}/data:/data',
                    '-w', '/work', 'sos-jmeter', '-n', '-t', 'plans/stress-stomp.jmx', '-l', f'results/stress-{n}.jtl',
                    '-Jhost=app', '-Jport=8080', f'-Jthreads={n}', f'-Jramp={RAMP}', f'-Jduration={DUR}', '-j', 'results/jmeter.log'],
                   env={**os.environ, 'MSYS_NO_PATHCONV': '1'}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    win = f'[{int(time.time() - t0)}s]'
    rows = list(csv.DictReader(open(jtl, encoding='utf-8', errors='replace')))
    by = {}
    for r in rows: by.setdefault(r['label'], []).append(r)
    lines.append(f'=== {n} threads  ({len(rows)} samples, {len(rows)/DUR:.0f}/s overall)')
    for lab, rs in sorted(by.items()):
        el = [int(r['elapsed']) for r in rs]; bad = sum(r['success'] != 'true' for r in rs)
        lines.append(f'  {lab:<42} n={len(rs):<6} p50={pct(el,.5):<5.0f} p95={pct(el,.95):<5.0f} p99={pct(el,.99):<5.0f} max={max(el):<6} err={bad}')
    peaks = {
        'tomcat busy max': f'max_over_time(tomcat_threads_busy_threads{win})',
        'hikari active max': f'max_over_time(hikaricp_connections_active{win})',
        'hikari pending max': f'max_over_time(hikaricp_connections_pending{win})',
        'inbound active max': f'max_over_time(executor_active_threads{{name="clientInboundChannelExecutor"}}{win})',
        'inbound queued max': f'max_over_time(executor_queued_tasks{{name="clientInboundChannelExecutor"}}{win})',
        'outbound active max': f'max_over_time(executor_active_threads{{name="clientOutboundChannelExecutor"}}{win})',
        'outbound queued max': f'max_over_time(executor_queued_tasks{{name="clientOutboundChannelExecutor"}}{win})',
        'process cpu max': f'max_over_time(process_cpu_usage{win})',
        'heap used max MB': f'max_over_time(sum(jvm_memory_used_bytes{{area="heap"}})[{win[1:-1]}:5s])/1048576',
        'live threads max': f'max_over_time(jvm_threads_live_threads{win})',
    }
    lines.append('  app: ' + '  '.join(f'{k}={prom(q):.2f}' for k, q in peaks.items()))
    errs = subprocess.run(['docker', 'compose', 'logs', 'app'], cwd=here, capture_output=True, text=True, errors='replace').stdout.count(' ERROR ')
    lines.append(f'  app ERROR log lines: {errs}')
    print('\n'.join(lines[-(len(by) + 3):]), flush=True)
open(os.path.join(here, 'results', 'stress-summary.txt'), 'w').write('\n'.join(lines) + '\n')
