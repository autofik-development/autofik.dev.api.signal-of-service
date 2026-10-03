# Generates the flow plans: plans/create-mix.jmx, bidding.jmx, lifecycle.jmx, geo.jmx.
#   create-mix  drivers create an SOS, read it back and cancel it (spike-shaped load on the create path)
#   bidding     a few drivers keep opening tickets; many mechanics bid on whichever are open; each
#               driver polls its sorted bid list and accepts the cheapest while bids are still arriving
#   lifecycle   each iteration is one whole job: create, bid, accept, ON_THE_WAY, ARRIVED, IN_PROGRESS,
#               propose + approve completion, feedback
#   geo         GET /mechanics/available from random points in Phnom Penh (H3 + PostGIS search)
# Every knob is a JMeter property (-Jname=value) with the default shown in ${__P(name,default)}.
# Run from load-tests/:  python plans/gen_flows.py
from xml.sax.saxutils import escape

def x(s):
    return escape(s, {'"': '&quot;'})

HOST = '<stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp><stringProp name="HTTPSampler.port">${__P(port,18080)}</stringProp>'
USER_CTX = '{"user":{"uuid":"${userId}","role":"USER","otpVerify":true,"profileVerify":true,"tokenStatus":"active","isLogout":false}}'
MECH_CTX = '{"mechanic":{"uuid":"${mechanicId}","otpVerify":true,"profileVerify":true,"tokenStatus":"active","isLogout":false}}'
USERS = ('/data/users.csv', 'userId,vehicleId,lat,lon,serviceGroupId')
MECHS = ('/data/mechanics-available.csv', 'mechanicId,mlat,mlng')

def plan(name, *groups):
    return ('<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.5"><hashTree>'
            f'<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="{x(name)}" enabled="true">'
            '<boolProp name="TestPlan.functional_mode">false</boolProp></TestPlan><hashTree>' + ''.join(groups) +
            '</hashTree></hashTree></jmeterTestPlan>')

def group(name, threads, ramp, duration, *children, delay='0', on_error='startnextloop'):
    return (f'<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="{x(name)}" enabled="true">'
            f'<stringProp name="ThreadGroup.on_sample_error">{on_error}</stringProp>'
            '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" enabled="true">'
            '<boolProp name="LoopController.continue_forever">false</boolProp><stringProp name="LoopController.loops">-1</stringProp></elementProp>'
            f'<stringProp name="ThreadGroup.num_threads">{threads}</stringProp><stringProp name="ThreadGroup.ramp_time">{ramp}</stringProp>'
            f'<boolProp name="ThreadGroup.scheduler">true</boolProp><stringProp name="ThreadGroup.duration">{duration}</stringProp>'
            f'<stringProp name="ThreadGroup.delay">{delay}</stringProp></ThreadGroup><hashTree>' + ''.join(children) + '</hashTree>')

def csv(spec, name):
    f, names = spec
    return (f'<CSVDataSet guiclass="TestBeanGUI" testclass="CSVDataSet" testname="{name}" enabled="true">'
            f'<stringProp name="filename">{f}</stringProp><stringProp name="variableNames">{names}</stringProp>'
            '<boolProp name="recycle">true</boolProp><boolProp name="stopThread">false</boolProp>'
            '<stringProp name="shareMode">shareMode.all</stringProp><boolProp name="quotedData">false</boolProp><stringProp name="delimiter">,</stringProp></CSVDataSet><hashTree/>')

def think(delay, rng):
    return (f'<UniformRandomTimer guiclass="UniformRandomTimerGui" testclass="UniformRandomTimer" testname="think" enabled="true">'
            f'<stringProp name="ConstantTimer.delay">{delay}</stringProp><stringProp name="RandomTimer.range">{rng}</stringProp></UniformRandomTimer><hashTree/>')

def pause(ms):
    """Flow Control Action: waits without recording a sample."""
    return ('<TestAction guiclass="TestActionGui" testclass="TestAction" testname="wait" enabled="true">'
            '<intProp name="ActionProcessor.action">1</intProp><intProp name="ActionProcessor.target">0</intProp>'
            f'<stringProp name="ActionProcessor.duration">{ms}</stringProp></TestAction><hashTree/>')

def groovy(kind, name, script):
    tag, gui = {'pre': ('JSR223PreProcessor', 'TestBeanGUI'), 'post': ('JSR223PostProcessor', 'TestBeanGUI'),
                'assert': ('JSR223Assertion', 'TestBeanGUI')}[kind]
    return (f'<{tag} guiclass="{gui}" testclass="{tag}" testname="{x(name)}" enabled="true">'
            '<stringProp name="scriptLanguage">groovy</stringProp><stringProp name="parameters"></stringProp>'
            '<stringProp name="filename"></stringProp><stringProp name="cacheKey">true</stringProp>'
            f'<stringProp name="script">{x(script)}</stringProp></{tag}><hashTree/>')

def picker(script):
    """A Flow Control Action with no pause whose pre-processor sets variables for what follows."""
    return ('<TestAction guiclass="TestActionGui" testclass="TestAction" testname="pick" enabled="true">'
            '<intProp name="ActionProcessor.action">1</intProp><intProp name="ActionProcessor.target">0</intProp>'
            '<stringProp name="ActionProcessor.duration">0</stringProp></TestAction><hashTree>' + groovy('pre', 'pick', script) + '</hashTree>')

def if_ctrl(cond, *children):
    return (f'<IfController guiclass="IfControllerPanel" testclass="IfController" testname="if" enabled="true">'
            f'<stringProp name="IfController.condition">{x(cond)}</stringProp><boolProp name="IfController.evaluateAll">false</boolProp>'
            '<boolProp name="IfController.useExpression">true</boolProp></IfController><hashTree>' + ''.join(children) + '</hashTree>')

def loop(n, *children):
    return ('<LoopController guiclass="LoopControlPanel" testclass="LoopController" testname="loop" enabled="true">'
            f'<boolProp name="LoopController.continue_forever">true</boolProp><stringProp name="LoopController.loops">{n}</stringProp></LoopController><hashTree>'
            + ''.join(children) + '</hashTree>')

def http(label, method, path, body=None, user=False, mech=False, codes=('200',), extract=(), extra=''):
    headers = []
    if body is not None:
        headers.append(('Content-Type', 'application/json'))
    if user:
        headers.append(('user-context', USER_CTX))
    if mech:
        headers.append(('mechanic-context', MECH_CTX))
    headers.append(('service-code', 'loadtest'))
    hdr = ''.join(f'<elementProp name="" elementType="Header"><stringProp name="Header.name">{n}</stringProp>'
                  f'<stringProp name="Header.value">{x(v)}</stringProp></elementProp>' for n, v in headers)
    bodyxml = ''
    if body is not None:
        bodyxml = ('<boolProp name="HTTPSampler.postBodyRaw">true</boolProp><elementProp name="HTTPsampler.Arguments" elementType="Arguments">'
                   '<collectionProp name="Arguments.arguments"><elementProp name="" elementType="HTTPArgument">'
                   f'<boolProp name="HTTPArgument.always_encode">false</boolProp><stringProp name="Argument.value">{x(body)}</stringProp>'
                   '<stringProp name="Argument.metadata">=</stringProp></elementProp></collectionProp></elementProp>')
    # test_type 40 = Equals | OR: any one of the listed codes passes
    strings = ''.join(f'<stringProp name="{i}">{c}</stringProp>' for i, c in enumerate(codes))
    assertion = (f'<ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="status {"|".join(codes)}" enabled="true">'
                 f'<collectionProp name="Asserion.test_strings">{strings}</collectionProp>'
                 '<stringProp name="Assertion.test_field">Assertion.response_code</stringProp><intProp name="Assertion.test_type">40</intProp>'
                 # assume_success: the listed codes decide, otherwise JMeter fails every 4xx before the assertion runs
                 '<boolProp name="Assertion.assume_success">true</boolProp></ResponseAssertion><hashTree/>')
    ex = ''.join(f'<JSONPostProcessor guiclass="JSONPostProcessorGui" testclass="JSONPostProcessor" testname="extract {v}" enabled="true">'
                 f'<stringProp name="JSONPostProcessor.referenceNames">{v}</stringProp><stringProp name="JSONPostProcessor.jsonPathExprs">{x(p)}</stringProp>'
                 f'<stringProp name="JSONPostProcessor.match_numbers">1</stringProp><stringProp name="JSONPostProcessor.defaultValues">NONE</stringProp></JSONPostProcessor><hashTree/>'
                 for v, p in extract)
    return (f'<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="{x(label)}" enabled="true">'
            + bodyxml + HOST + f'<stringProp name="HTTPSampler.path">{x(path)}</stringProp><stringProp name="HTTPSampler.method">{method}</stringProp>'
            '</HTTPSamplerProxy><hashTree><HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="hdr" enabled="true">'
            f'<collectionProp name="HeaderManager.headers">{hdr}</collectionProp></HeaderManager><hashTree/>' + assertion + ex + extra + '</hashTree>')

CREATE_BODY = ('{"userId":"${userId}","serviceGroupId":"${serviceGroupId}","description":"loadtest","latitude":${lat},'
               '"longitude":${lon},"vehicleId":"${vehicleId}","broadcastRadiusKm":15}')

def create(extra=''):
    return http('POST /api/v1/sos', 'POST', '/api/v1/sos', CREATE_BODY, user=True, codes=('201',),
                extract=[('sosId', '$.sosId')], extra=extra)

# ---------------------------------------------------------------------------- create-mix
create_mix = plan('SOS create mix',
    group('drivers', '${__P(threads,500)}', '${__P(ramp,60)}', '${__P(duration,420)}',
          csv(USERS, 'users'), think('${__P(think_ms,1000)}', '${__P(think_range_ms,2000)}'),
          create(),
          if_ctrl('${__jexl3("${sosId}" != "NONE")}',
                  http('GET /api/v1/sos/{id}', 'GET', '/api/v1/sos/${sosId}', user=True),
                  http('PATCH /api/v1/sos/{id}/cancel', 'PATCH', '/api/v1/sos/${sosId}/cancel', user=True))))

# ---------------------------------------------------------------------------- bidding
# Open tickets live in a shared property list; drivers add theirs and take them out just before
# accepting, so in-flight bids still race the accept (the case the integrity checks look at).
OPEN_ADD = '''if (prev.getResponseCode() == "201") {
  def open = props.get("openSos"); if (open == null) { synchronized (props) { open = props.get("openSos"); if (open == null) { open = new java.util.concurrent.CopyOnWriteArrayList(); props.put("openSos", open) } } }
  open.add(vars.get("sosId"))
}'''
OPEN_REMOVE = 'def open = props.get("openSos"); if (open != null) open.remove(vars.get("sosId"))'
PICK_SOS = '''def open = props.get("openSos")
if (open == null || open.isEmpty()) { vars.put("bidSos", "NONE"); return }
try { vars.put("bidSos", open.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(open.size()))) } catch (IndexOutOfBoundsException e) { vars.put("bidSos", "NONE") }
vars.put("price", String.format("%.2f", 10 + java.util.concurrent.ThreadLocalRandom.current().nextDouble(90)))'''
SORTED_CHEAPEST = '''if (prev.getResponseCode() != "200") return
def bids = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
def prices = bids.collect { new BigDecimal(it.price.toString()) }
for (int i = 1; i < prices.size(); i++) if (prices[i] < prices[i - 1]) { AssertionResult.setFailure(true); AssertionResult.setFailureMessage("CHEAPEST not sorted at " + i + ": " + prices[i - 1] + " > " + prices[i]); return }'''

bidding = plan('SOS bidding war',
    group('drivers', '${__P(drivers,10)}', '5', '${__P(duration,210)}',
          csv(USERS, 'users'),
          create(extra=groovy('post', 'publish open ticket', OPEN_ADD)),
          if_ctrl('${__jexl3("${sosId}" != "NONE")}',
                  loop('${__P(polls,4)}',
                       pause('${__P(poll_ms,5000)}'),
                       http('GET /api/v1/bids/sos/{id}?sortBy=CHEAPEST', 'GET', '/api/v1/bids/sos/${sosId}?sortBy=CHEAPEST', user=True,
                            extract=[('cheapestBid', "$[?(@.status == 'PENDING')].bidId")],
                            extra=groovy('assert', 'CHEAPEST is ascending', SORTED_CHEAPEST))),
                  if_ctrl('${__jexl3("${cheapestBid}" != "NONE")}',
                          http('PATCH /api/v1/bids/{id}/status ACCEPTED', 'PATCH', '/api/v1/bids/${cheapestBid}/status',
                               '{"userId":"${userId}","status":"ACCEPTED"}', user=True,
                               extra=groovy('pre', 'close ticket to new bidders', OPEN_REMOVE))),
                  pause('1000')), on_error='continue'),
    group('mechanics', '${__P(mechanics,200)}', '${__P(ramp,30)}', '${__P(duration,210)}',
          csv(MECHS, 'mechanics'), think('${__P(think_ms,200)}', '${__P(think_range_ms,800)}'),
          picker(PICK_SOS),
          if_ctrl('${__jexl3("${bidSos}" != "NONE")}',
                  # 409 is the service doing its job (already bid, window closed, ticket accepted)
                  http('POST /api/v1/bids', 'POST', '/api/v1/bids',
                       '{"mechanicId":"${mechanicId}","sosId":"${bidSos}","price":${price}}', mech=True, codes=('201', '409')))))

# ---------------------------------------------------------------------------- lifecycle
def step(to):
    return http(f'PATCH /sos/{{id}}/status {to}', 'PATCH', f'/api/v1/sos/${{sosId}}/status?toStatus={to}', mech=True)

lifecycle = plan('SOS lifecycle',
    group('jobs', '${__P(threads,50)}', '${__P(ramp,30)}', '${__P(duration,600)}',
          csv(USERS, 'users'), csv(MECHS, 'mechanics'), think('${__P(think_ms,200)}', '${__P(think_range_ms,300)}'),
          create(),
          # the mechanic drives to the breakdown; ARRIVED is refused more than 50 m away
          http('PATCH /api/v1/mechanics/{id}/location', 'PATCH', '/api/v1/mechanics/${mechanicId}/location?lat=${lat}&lng=${lon}', mech=True, codes=('204',)),
          http('POST /api/v1/bids', 'POST', '/api/v1/bids', '{"mechanicId":"${mechanicId}","sosId":"${sosId}","price":25.00}',
               mech=True, codes=('201',), extract=[('bidId', '$.bidId')]),
          http('PATCH /api/v1/bids/{id}/status ACCEPTED', 'PATCH', '/api/v1/bids/${bidId}/status', '{"userId":"${userId}","status":"ACCEPTED"}', user=True),
          step('ON_THE_WAY'), step('ARRIVED'), step('IN_PROGRESS'),
          http('POST /sos/{id}/completion', 'POST', '/api/v1/sos/${sosId}/completion', '{"mechanicId":"${mechanicId}","toStatus":"COMPLETED"}',
               mech=True, codes=('201',), extract=[('historyId', '$.id')]),
          http('PATCH /sos/completion ACCEPTED', 'PATCH', '/api/v1/sos/completion',
               '{"userId":"${userId}","historyId":"${historyId}","approvedStatus":"ACCEPTED"}', user=True),
          http('POST /api/v1/feedback', 'POST', '/api/v1/feedback',
               '{"userId":"${userId}","mechanicId":"${mechanicId}","sosId":"${sosId}","rating":${__Random(1,5)},"comment":"loadtest"}',
               user=True, codes=('201',))))

# ---------------------------------------------------------------------------- geo
geo = plan('SOS geo search',
    group('searchers', '${__P(threads,200)}', '${__P(ramp,30)}', '${__P(duration,300)}',
          csv(USERS, 'users'), think('${__P(think_ms,200)}', '${__P(think_range_ms,800)}'),
          http('GET /api/v1/mechanics/available', 'GET',
               '/api/v1/mechanics/available?lat=${lat}&lng=${lon}&problemType=TOW&radiusKm=${__Random(5,20)}', user=True)))

for name, body in [('create-mix', create_mix), ('bidding', bidding), ('lifecycle', lifecycle), ('geo', geo)]:
    with open(f'plans/{name}.jmx', 'w', encoding='utf-8') as f:
        f.write(body)
    print(f'wrote plans/{name}.jmx', len(body), 'bytes')
