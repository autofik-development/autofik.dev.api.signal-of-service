# Generates plans/smoke-stomp.jmx from plans/smoke.jmx (reuses its thread group, CSVs, timer and create-SOS sampler).
import re, html
src = open('plans/smoke.jmx', encoding='utf-8').read()
tg_head = re.search(r'<ThreadGroup.*?</ThreadGroup><hashTree>', src, re.S).group(0).replace('testname="smoke"', 'testname="stomp smoke"')
csvs = ''.join(re.findall(r'<CSVDataSet.*?</CSVDataSet><hashTree/>', src, re.S))
timer = re.search(r'<UniformRandomTimer.*?</UniformRandomTimer><hashTree/>', src, re.S).group(0)
create = re.search(r'<HTTPSamplerProxy[^>]*testname="POST /api/v1/sos".*?</JSONPostProcessor><hashTree/></hashTree>', src, re.S).group(0)

def groovy(name, code):
    return (f'<JSR223Sampler guiclass="TestBeanGUI" testclass="JSR223Sampler" testname="{name}" enabled="true">'
            f'<stringProp name="scriptLanguage">groovy</stringProp><stringProp name="parameters"></stringProp>'
            f'<stringProp name="filename"></stringProp><stringProp name="cacheKey">true</stringProp>'
            f'<stringProp name="script">{html.escape(code, quote=False)}</stringProp></JSR223Sampler><hashTree/>')

HELP = r'''
import java.net.URI
import java.net.http.*
import java.util.concurrent.*
def nul = "" + (char) 0
def ctx = { String key, String id -> '{"' + key + '":{"uuid":"' + id + '",' + (key == 'user' ? '"role":"USER",' : '') + '"otpVerify":true,"profileVerify":true,"tokenStatus":"active","isLogout":false}}' }
'''
connect = HELP + r'''
def host = props.getProperty('host', 'localhost') + ':' + props.getProperty('port', '18080')
def client = HttpClient.newHttpClient()
def open = { String hdr, String c, BlockingQueue q ->
  client.newWebSocketBuilder().header(hdr, c).buildAsync(URI.create('ws://' + host + '/ws'), new WebSocket.Listener() {
    StringBuilder sb = new StringBuilder()
    void onOpen(WebSocket ws) { ws.request(1) }
    CompletionStage onText(WebSocket ws, CharSequence d, boolean last) { sb.append(d); if (last) { q.add(sb.toString()); sb.setLength(0) }; ws.request(1); return null }
    void onError(WebSocket ws, Throwable t) { q.add('ERROR ' + t) }
    CompletionStage onClose(WebSocket ws, int s, String r) { q.add('CLOSE ' + s); return null }
  }).get(5, TimeUnit.SECONDS)
}
def cq = new LinkedBlockingQueue(), mq = new LinkedBlockingQueue()
def cust = open('user-context', ctx('user', vars.get('userId')), cq)
def mech = open('mechanic-context', ctx('mechanic', vars.get('mechanicId')), mq)
vars.putObject('cust', cust); vars.putObject('mech', mech); vars.putObject('cq', cq); vars.putObject('mq', mq)
cust.sendText('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n' + nul, true)
mech.sendText('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n' + nul, true)
def a = cq.poll(5, TimeUnit.SECONDS), b = mq.poll(5, TimeUnit.SECONDS)
if (!a?.startsWith('CONNECTED') || !b?.startsWith('CONNECTED')) throw new Exception('no CONNECTED: ' + a + ' / ' + b)
cust.sendText('SUBSCRIBE\nid:s1\nreceipt:r1\ndestination:/topic/sos/' + vars.get('sosId') + '/location\n\n' + nul, true)
// Spring's simple broker does not answer a receipt header, so wait a beat for the subscription to register.
Thread.sleep(200)
'''
ping = HELP + r'''
def cq = vars.getObject('cq'), mech = vars.getObject('mech')
mech.sendText('SEND\ndestination:/app/mechanic/location\ncontent-type:application/json\n\n{"latitude":' + vars.get('mlat') + ',"longitude":' + vars.get('mlng') + ',"sosId":"' + vars.get('sosId') + '"}' + nul, true)
def m = cq.poll(5, TimeUnit.SECONDS)
if (m == null || !m.startsWith('MESSAGE')) throw new Exception('no broadcast: ' + m)
'''
close = HELP + r'''
['cust', 'mech'].each { vars.getObject(it)?.sendClose(1000, 'bye') }
def e = vars.getObject('mq')?.poll(100, TimeUnit.MILLISECONDS)
if (e != null && e.startsWith('ERROR')) throw new Exception(e)
'''
loop = ('<LoopController guiclass="LoopControlPanel" testclass="LoopController" testname="pings" enabled="true">'
        '<stringProp name="LoopController.loops">${__P(pings,3)}</stringProp></LoopController><hashTree>'
        + groovy('STOMP ping -> broadcast', ping) + timer + '</hashTree>')
tg = tg_head + csvs + create + groovy('STOMP connect+subscribe', connect) + loop + groovy('STOMP close', close) + '</hashTree>'
open('plans/smoke-stomp.jmx', 'w', encoding='utf-8').write(
    '<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>'
    '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="SOS STOMP smoke" enabled="true">'
    '<boolProp name="TestPlan.functional_mode">false</boolProp></TestPlan><hashTree>' + tg + '</hashTree></hashTree></jmeterTestPlan>')

# ---- stress plan: each thread creates one SOS and opens its sockets once, then pings until the scheduler stops it.
def grab(label):
    return re.search(r'<HTTPSamplerProxy[^>]*testname="%s".*?</HTTPSamplerProxy><hashTree>.*?</hashTree>' % re.escape(label), src, re.S).group(0)
get_sos = grab('GET /api/v1/sos/{id}')
patch_loc = grab('PATCH /api/v1/mechanics/{id}/location')
forever = ('<LoopController guiclass="LoopControlPanel" testclass="LoopController" testname="until duration" enabled="true">'
           '<stringProp name="LoopController.loops">-1</stringProp></LoopController><hashTree>'
           + groovy('STOMP ping -> broadcast', ping) + get_sos + patch_loc + timer + '</hashTree>')
head = tg_head.replace('testname="stomp smoke"', 'testname="stress"').replace(
    '<stringProp name="LoopController.loops">-1</stringProp>', '<stringProp name="LoopController.loops">1</stringProp>')
tg2 = head + csvs + create + groovy('STOMP connect+subscribe', connect) + forever + '</hashTree>'
open('plans/stress-stomp.jmx', 'w', encoding='utf-8').write(
    '<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree>'
    '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="SOS stress" enabled="true">'
    '<boolProp name="TestPlan.functional_mode">false</boolProp></TestPlan><hashTree>' + tg2 + '</hashTree></hashTree></jmeterTestPlan>')
