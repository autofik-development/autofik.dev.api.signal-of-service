#!/usr/bin/env python3
"""Turns Surefire XML (and the JaCoCo CSV for unit tests) into reports/report-<type>-tests.{md,json}.

    python scripts/test-report.py unit        --surefire reports/data/surefire-unit        --jacoco reports/data/jacoco-unit.csv
    python scripts/test-report.py integration --surefire reports/data/surefire-integration
    python scripts/test-report.py fuzz        --surefire reports/data/surefire-fuzz

Test classes are filed by name: *Fuzz* -> fuzz, package `integration` -> integration, the rest -> unit,
so one Surefire directory can feed all three. Hand-written findings go in reports/notes/<type>-tests.md
and are appended verbatim, so regenerating a report never loses them.
Exit code: 1 if any test in this type failed, so CI can use it as the gate.
"""
import argparse, csv, datetime as dt, json, pathlib, platform, re, subprocess, sys
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parent.parent
REPORTS = REPO / 'reports'

PURPOSE = {
    'unit': 'Fast, isolated checks of business rules, validation, mapping, (de)serialization and the Kafka/Redis/STOMP handler logic. '
            'Every external system (Postgres, Kafka, Redis, Medusa, Google, OneSignal, S3) is mocked or replaced by a local stub, so nothing needs Docker or a network.',
    'integration': 'The whole Spring Boot app against real PostgreSQL/PostGIS, Redis and Kafka started by Testcontainers: REST requests, STOMP over a real WebSocket, '
                   'Kafka to STOMP, Redis pub/sub, and what happens when Redis or Kafka stalls or disappears. Only Medusa, Google and OneSignal are mocked.',
    'fuzz': 'Randomised and malformed input thrown at the REST layer, the gateway identity header, STOMP subscriptions and frames, the location ping, and the Kafka consumers, '
            'looking for crashes (5xx), hangs, leaks across users and validation gaps. Seeds are pinned so a run is repeatable.'}

COMMAND = {
    'unit': "./mvnw test -Dtest='!SosApplicationTests,!*FuzzTest,!*FuzzIntegrationTest'",
    'integration': "./mvnw test -Pintegration -Dtest='*IntegrationTest,!*Fuzz*'",
    'fuzz': './mvnw test -Pfuzz'}


def classify(classname):
    short = classname.rsplit('.', 1)[-1]
    if 'Fuzz' in short or '.fuzz.' in classname:
        return 'fuzz'
    return 'integration' if '.integration.' in classname else 'unit'


def load(dir_, kind):
    suites, cases = [], []
    for f in sorted(pathlib.Path(dir_).glob('TEST-*.xml')):
        root = ET.parse(f).getroot()
        # Surefire puts captured output on the suite or on each test case, depending on settings.
        out = '\n'.join((el.text or '') for el in root.iter() if el.tag in ('system-out', 'system-err'))
        for tc in root.iter('testcase'):
            cn = tc.get('classname', '')
            if classify(cn) != kind:
                continue
            status, detail = 'passed', ''
            for tag, st in (('failure', 'failed'), ('error', 'error'), ('skipped', 'skipped')):
                el = tc.find(tag)
                if el is not None:
                    status, detail = st, ((el.get('message') or '') + '\n' + (el.text or '')).strip()
                    break
            cases.append({'class': cn, 'name': tc.get('name', ''), 'time': float(tc.get('time') or 0), 'status': status, 'detail': detail})
        if any(classify(c) == kind for c in {tc.get('classname', '') for tc in root.iter('testcase')}):
            suites.append({'name': root.get('name'), 'out': out})
    return suites, cases


def jacoco(path):
    if not path or not pathlib.Path(path).exists():
        return None
    rows = list(csv.DictReader(open(path, encoding='utf-8')))
    def tot(rs, k):
        c, m = sum(int(r[k + '_COVERED']) for r in rs), sum(int(r[k + '_MISSED']) for r in rs)
        return c, m, (100 * c / (c + m) if c + m else 100.0)
    pk = {}
    for r in rows:
        pk.setdefault(r['PACKAGE'].replace('com.autofik.sos', '').lstrip('.') or '(root)', []).append(r)
    classes = [{'name': r['PACKAGE'].replace('com.autofik.sos.', '') + '.' + r['CLASS'], 'missed': int(r['LINE_MISSED']), 'covered': int(r['LINE_COVERED']),
                'pct': 100 * int(r['LINE_COVERED']) / max(1, int(r['LINE_MISSED']) + int(r['LINE_COVERED']))} for r in rows if int(r['LINE_MISSED']) + int(r['LINE_COVERED'])]
    return {'instruction': tot(rows, 'INSTRUCTION'), 'branch': tot(rows, 'BRANCH'), 'line': tot(rows, 'LINE'), 'method': tot(rows, 'METHOD'),
            'packages': {k: tot(v, 'LINE') for k, v in sorted(pk.items())}, 'classes': classes}


def sh1(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        return (r.stdout + r.stderr).strip().splitlines()[0]
    except Exception:
        return 'n/a'


def sh1_all(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        return (r.stdout + r.stderr).strip().splitlines()
    except Exception:
        return []


def environment(kind):
    pom = (REPO / 'pom.xml').read_text(encoding='utf-8')
    boot = re.search(r'spring-boot-starter-parent</artifactId>\s*<version>([^<]+)', pom)
    jv = next((l for l in sh1_all(['java', '-version']) if 'version "' in l), 'n/a')
    env = {'Java': jv, 'Spring Boot': boot.group(1) if boot else 'n/a', 'Build tool': 'Maven wrapper (mvnw)',
           'Machine': f'{platform.system()} {platform.release()}, {platform.processor() or platform.machine()}'}
    try:
        import os
        env['CPU cores'] = str(os.cpu_count())
    except Exception:
        pass
    if kind == 'integration':
        base = (REPO / 'src/test/java/com/autofik/sos/integration/IntegrationTestBase.java').read_text(encoding='utf-8')
        imgs = re.findall(r'"((?:postgis/postgis|redis|confluentinc/cp-kafka)[^"]*)"', base)
        env['Containers (Testcontainers)'] = ', '.join(imgs)
        env['Mocked'] = 'Medusa, Google Maps, OneSignal (@MockitoBean); S3 not exercised'
    elif kind == 'fuzz':
        env['Fuzz engine'] = 'jqwik 1.9.3 (properties) and seeded java.util.Random (STOMP frames)'
    else:
        env['Test stack'] = 'JUnit 5, Mockito, AssertJ, Spring test slices; no containers'
    return env


def md_table(headers, rows):
    return '\n'.join(['| ' + ' | '.join(headers) + ' |', '|' + '|'.join('---' for _ in headers) + '|'] + ['| ' + ' | '.join(str(c) for c in r) + ' |' for r in rows])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('kind', choices=['unit', 'integration', 'fuzz'])
    ap.add_argument('--surefire', default='target/surefire-reports')
    ap.add_argument('--jacoco')
    ap.add_argument('--baseline-jacoco', help='coverage CSV from before this work, for the delta line')
    ap.add_argument('--elapsed', help='wall clock of the whole Maven run, e.g. "4m 12s" (optional)')
    a = ap.parse_args()
    suites, cases = load(REPO / a.surefire if not pathlib.Path(a.surefire).is_absolute() else a.surefire, a.kind)
    if not cases:
        print(f'no {a.kind} test results found in {a.surefire}', file=sys.stderr)
        sys.exit(2)
    n = len(cases)
    cnt = {s: sum(c['status'] == s for c in cases) for s in ('passed', 'failed', 'error', 'skipped')}
    total_time = sum(c['time'] for c in cases)
    cov = jacoco(a.jacoco) if a.kind == 'unit' else None
    base = jacoco(a.baseline_jacoco) if a.baseline_jacoco else None
    by_class = {}
    for c in cases:
        d = by_class.setdefault(c['class'].replace('com.autofik.sos.', ''), {'tests': 0, 'failed': 0, 'skipped': 0, 'time': 0.0})
        d['tests'] += 1; d['time'] += c['time']; d['failed'] += c['status'] in ('failed', 'error'); d['skipped'] += c['status'] == 'skipped'
    findings = [l.split('] ', 1)[1].strip() if '] ' in l else l for s in suites for l in s['out'].splitlines() if '[finding]' in l]
    repros = [l.strip() for s in suites for l in s['out'].splitlines() if l.startswith('[fuzz-repro]')]
    failures = [c for c in cases if c['status'] in ('failed', 'error')]
    ok = not failures

    md = [f'# {a.kind.capitalize()} test report', '',
          f"Generated {dt.datetime.now().strftime('%Y-%m-%d %H:%M')} from `{a.surefire}`. Result: " + ('**PASS**' if ok else f'**FAIL** ({len(failures)} failing)'), '',
          '## 1. Overview', '', PURPOSE[a.kind], '', md_table(['Environment', ''], list(environment(a.kind).items())), '',
          '## 2. Configuration', '', md_table(['Setting', 'Value'], [
              ['Command', f'`{COMMAND[a.kind]}`'], ['Logs', 'target/logs/' + a.kind + '.log (full Maven output)'], ['Raw results', f'reports/data/surefire-{a.kind}/ (Surefire XML)'],
              ['Tag / profile', {'unit': 'default build (integration-tagged tests excluded)', 'integration': 'profile `integration`, JUnit tag `integration`, needs Docker', 'fuzz': 'profile `fuzz`, deeper than the PR build'}[a.kind]]
              + ([] if a.kind != 'fuzz' else [])]
              + ([['Fuzz depth', 'jqwik tries 5000 (PR build: 200), REST fuzz tries 500 (PR: 150), STOMP frames 400; seeds pinned (`Fuzz.SEED`, `-Dfuzz.seed`)']] if a.kind == 'fuzz' else [])), '',
          '## 3. Results', '', md_table(['Tests', 'Passed', 'Failed', 'Errors', 'Skipped', 'Test time (sum)'], [[n, cnt['passed'], cnt['failed'], cnt['error'], cnt['skipped'], f'{total_time:.1f} s']]), '']
    if cov:
        md += ['### Coverage (JaCoCo, unit tests only)', '',
               md_table(['Metric', 'Covered', 'Missed', 'Coverage'] + (['Before this work'] if base else []),
                        [[k.capitalize(), f'{cov[k][0]:,}', f'{cov[k][1]:,}', f'{cov[k][2]:.1f} %'] + ([f'{base[k][2]:.1f} %'] if base else []) for k in ('line', 'branch', 'method', 'instruction')]), '',
               'By package (line coverage):', '', md_table(['Package', 'Covered lines', 'Missed lines', 'Coverage'], [[k, v[0], v[1], f'{v[2]:.0f} %'] for k, v in cov['packages'].items()]), '']
        low = sorted([c for c in cov['classes'] if c['missed'] >= 10 and c['pct'] < 60], key=lambda c: -c['missed'])[:12]
        if low:
            md += ['Largest uncovered classes (10+ missed lines, under 60 %):', '', md_table(['Class', 'Missed lines', 'Coverage'], [[c['name'], c['missed'], f"{c['pct']:.0f} %"] for c in low]), '']
    md += ['### By test class', '', md_table(['Class', 'Tests', 'Failed', 'Skipped', 'Time'], [[k, v['tests'], v['failed'], v['skipped'], f"{v['time']:.1f} s"] for k, v in sorted(by_class.items())]), '']
    if failures:
        md += ['### Failures', '']
        for c in failures[:25]:
            md += [f"**{c['class'].replace('com.autofik.sos.', '')}.{c['name']}** ({c['status']})", '', '```', '\n'.join(c['detail'].splitlines()[:25])[:2500], '```', '']
        if repros:
            md += ['Reproduction lines printed by the fuzz tests:', ''] + [f'- `{r[:600]}`' for r in repros] + ['']
    if a.kind == 'fuzz':
        md += ['### Reproducing a fuzz failure', '', 'Failures print the jqwik seed and the shrunk sample (see the failure text above). Re-run just that property with the printed seed:',
               '`./mvnw test -Dtest=<Class>#<method> -Djqwik.seeds.whenfixed=ALLOW -Djqwik.tries.default=<n>` after setting `Fuzz.SEED` to the printed value. '
               'jqwik also remembers failing seeds in `target/.jqwik-database` and replays them first. STOMP frame failures print `[fuzz-repro]` with the seed and the exact frame.', '']
    md += ['## 4. Findings', '']
    fl = []
    if failures:
        fl.append(f'{len(failures)} test(s) failed; details above.')
    if cnt['skipped']:
        fl.append(f"{cnt['skipped']} test(s) skipped.")
    fl += findings
    if cov:
        fl.append(f"Line coverage {cov['line'][2]:.1f} %, branch coverage {cov['branch'][2]:.1f} %" + (f" (was {base['line'][2]:.1f} % / {base['branch'][2]:.1f} % before this work)." if base else '.'))
    if not fl:
        fl.append('All tests passed; nothing unexpected observed.')
    md += [f'- {x}' for x in fl] + ['', '## 5. Recommendations', '']
    rec = []
    if failures:
        rec.append('Fix the failing tests first; they are listed with their stack traces above.')
    if cov:
        low = sorted([c for c in cov['classes'] if c['missed'] >= 20 and c['pct'] < 60], key=lambda c: -c['missed'])[:5]
        if low:
            rec.append('Next coverage targets by missed lines: ' + ', '.join(f"{c['name']} ({c['missed']})" for c in low) + '.')
    if a.kind == 'integration':
        rec.append('Keep this suite out of CI and the fast PR check; run it locally with `scripts/run-tests.sh`.')
    if a.kind == 'fuzz':
        rec.append('Run the deeper `-Pfuzz` pass on a schedule or before releases, with a fresh `-Dfuzz.seed`, and keep every failing seed as a regression test.')
    md += [f'{i}. {x}' for i, x in enumerate(rec or ['No action needed from this run.'], 1)]
    notes = REPORTS / 'notes' / f'{a.kind}-tests.md'
    if notes.exists():
        md += ['', '## Analyst notes', '', notes.read_text(encoding='utf-8').strip()]
    REPORTS.mkdir(exist_ok=True)
    (REPORTS / f'report-{a.kind}-tests.md').write_text('\n'.join(md) + '\n', encoding='utf-8')
    (REPORTS / f'report-{a.kind}-tests.json').write_text(json.dumps({
        'type': a.kind, 'generated': dt.datetime.now().isoformat(timespec='seconds'), 'passed': ok, 'tests': n, **cnt, 'time_s': round(total_time, 1),
        'coverage': None if not cov else {k: round(cov[k][2], 2) for k in ('line', 'branch', 'method', 'instruction')},
        'classes': by_class, 'failures': [{'test': f"{c['class']}.{c['name']}", 'detail': c['detail'][:2000]} for c in failures], 'findings': findings}, indent=2), encoding='utf-8')
    print(f"reports/report-{a.kind}-tests.md: {n} tests, {len(failures)} failing, {cnt['skipped']} skipped")
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
