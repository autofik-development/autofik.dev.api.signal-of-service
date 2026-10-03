import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.*;

public class Probe {
  static String ctx(String key, String id) {
    return "{\"" + key + "\":{\"uuid\":\"" + id + "\"," + (key.equals("user") ? "\"role\":\"USER\"," : "") + "\"otpVerify\":true,\"profileVerify\":true,\"tokenStatus\":\"active\",\"isLogout\":false}}";
  }
  static WebSocket open(HttpClient c, String host, String hdr, String ctx, BlockingQueue<String> q) throws Exception {
    return c.newWebSocketBuilder().header(hdr, ctx).buildAsync(URI.create("ws://" + host + "/ws"),
      new WebSocket.Listener() {
        StringBuilder sb = new StringBuilder();
        public CompletionStage<?> onText(WebSocket ws, CharSequence d, boolean last) {
          sb.append(d); if (last) { q.add(sb.toString()); sb.setLength(0); } ws.request(1); return null; }
        public void onOpen(WebSocket ws) { ws.request(1); }
        public void onError(WebSocket ws, Throwable t) { q.add("ERROR " + t); }
        public CompletionStage<?> onClose(WebSocket ws, int s, String r) { q.add("CLOSE " + s + " " + r); return null; }
      }).get(5, TimeUnit.SECONDS);
  }
  public static void main(String[] a) throws Exception {
    String host = a[0], user = a[1], veh = a[2], mech = a[3];
    HttpClient c = HttpClient.newHttpClient();
    var req = HttpRequest.newBuilder(URI.create("http://" + host + "/api/v1/sos"))
      .header("Content-Type", "application/json").header("user-context", ctx("user", user))
      .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"" + user + "\",\"serviceGroupId\":\"sg_loadtest\",\"latitude\":11.56,\"longitude\":104.92,\"vehicleId\":\"" + veh + "\",\"broadcastRadiusKm\":10}")).build();
    String body = c.send(req, HttpResponse.BodyHandlers.ofString()).body();
    String sosId = body.replaceAll(".*\"sosId\":\"([^\"]+)\".*", "$1");
    System.out.println("sos " + sosId);
    BlockingQueue<String> cq = new LinkedBlockingQueue<>(), mq = new LinkedBlockingQueue<>();
    WebSocket cust = open(c, host, "user-context", ctx("user", user), cq);
    WebSocket mec = open(c, host, "mechanic-context", ctx("mechanic", mech), mq);
    cust.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0", true);
    mec.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0", true);
    System.out.println("cust: " + cq.poll(3, TimeUnit.SECONDS));
    System.out.println("mech: " + mq.poll(3, TimeUnit.SECONDS));
    cust.sendText("SUBSCRIBE\nid:s1\ndestination:/topic/sos/" + sosId + "/location\n\n\0", true);
    Thread.sleep(300);
    long t0 = System.nanoTime();
    mec.sendText("SEND\ndestination:/app/mechanic/location\ncontent-type:application/json\n\n{\"latitude\":11.57,\"longitude\":104.93,\"sosId\":\"" + sosId + "\"}\0", true);
    System.out.println("cust got: " + cq.poll(5, TimeUnit.SECONDS) + "  after ms " + (System.nanoTime() - t0) / 1_000_000);
    System.out.println("mech queue: " + mq.poll(1, TimeUnit.SECONDS));
  }
}
