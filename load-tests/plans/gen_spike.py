# Generates plans/spike-stomp.jmx from plans/stress-stomp.jmx: two thread groups in one plan.
#   baseline  - ${base} threads (default 10) for the whole run
#   spike     - ${spike} threads (default 500) that start at ${spike_at} s, ramp in ${spike_ramp} s
#               (default 5) and stop ${spike_len} s after they started
# Each thread does what a stress thread does (create one SOS, open two sockets, ping/GET/PATCH in a
# loop). The spike group reads the user and mechanic CSVs back to front so its first users are not the
# baseline's (a user with a live ticket gets 409).
import re
src = open('plans/stress-stomp.jmx', encoding='utf-8').read()
m = re.search(r'(<ThreadGroup.*?</ThreadGroup><hashTree>.*?</hashTree>)(</hashTree></hashTree></jmeterTestPlan>)', src, re.S)
group, tail = m.group(1), m.group(2)
head = src[:m.start(1)]

def tg(name, threads, ramp, delay, duration, csv_suffix):
    g = group.replace('testname="stress"', f'testname="{name}"')
    g = re.sub(r'(ThreadGroup.num_threads">)[^<]*', r'\g<1>' + threads, g)
    g = re.sub(r'(ThreadGroup.ramp_time">)[^<]*', r'\g<1>' + ramp, g)
    g = re.sub(r'(ThreadGroup.duration">)[^<]*', r'\g<1>' + duration, g)
    g = g.replace('<boolProp name="ThreadGroup.scheduler">true</boolProp>',
                  '<boolProp name="ThreadGroup.scheduler">true</boolProp><stringProp name="ThreadGroup.delay">' + delay + '</stringProp>')
    if csv_suffix:
        g = g.replace('data/users.csv', f'data/users{csv_suffix}.csv').replace('data/mechanics.csv', f'data/mechanics{csv_suffix}.csv')
    return g

baseline = tg('baseline', '${__P(base,10)}', '5', '0', '${__P(duration,330)}', '')
spike = tg('spike', '${__P(spike,500)}', '${__P(spike_ramp,5)}', '${__P(spike_at,60)}', '${__P(spike_len,125)}', '-rev')
out = head.replace('SOS stress', 'SOS spike') + baseline + spike + tail
open('plans/spike-stomp.jmx', 'w', encoding='utf-8').write(out)
print('wrote plans/spike-stomp.jmx', len(out), 'bytes;', out.count('<ThreadGroup '), 'thread groups')
