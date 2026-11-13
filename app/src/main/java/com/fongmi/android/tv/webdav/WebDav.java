package com.fongmi.android.tv.webdav;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.catvod.net.interceptor.FailoverInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

public class WebDav {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(new FailoverInterceptor()) // 域名连不上时静默换备用域名再试一次
            .authenticator(new DigestAuth()) // 自建 WebDAV 常用 Digest 验证，收到 401 挑战后自动补上
            .build();

    private static String auth() {
        return Credentials.basic(WebDavSetting.getUser(), WebDavSetting.getPass());
    }

    private static Request.Builder prepare(String url) throws IOException {
        // 没配地址时 OkHttp 会抛 IllegalArgumentException，改成可控的 IOException，
        // 连接测试/同步入口都能正常收到「服务地址为空」，不会因为空配置冒出怪异常。
        if (url == null || url.isEmpty()) throw new IOException("Address is empty");
        Request.Builder builder = new Request.Builder().url(url).addHeader("Accept", "*/*");
        // 「无需登录」或压根没填账号密码的服务，空账号也带 Authorization 反而会被拒，干脆不带
        if (WebDavSetting.hasAuth()) builder.addHeader("Authorization", auth());
        return builder;
    }

    /**
     * 目录/文件是否存在（PROPFIND Depth:0）。
     * 207/200 = 存在；404 = 不存在；其他按异常抛出，方便把真实原因报给用户。
     */
    public static boolean exists(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).method("PROPFIND", null).addHeader("Depth", "0").build()).execute()) {
            int code = response.code();
            if (code == 207 || code == 200) return true;
            if (code == 404) return false;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            throw new IOException("PROPFIND failed (" + code + ")");
        }
    }

    public static void createFolder(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).method("MKCOL", null).build()).execute()) {
            int code = response.code();
            if (code == 201 || code == 200 || code == 204 || code == 301 || code == 302) return;
            if (code == 405 || code == 409) return;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            throw new IOException("MKCOL failed (" + code + ")");
        }
    }

    /**
     * 同步前调用：逐级确认目录存在，缺了才 MKCOL。
     * 之前的问题是只在部分路径检查过，远端目录不存在时 PUT 会直接 409/404 报错。
     */
    public static void createFolders() throws IOException {
        String base = WebDavSetting.getFolderUrl();
        if (base.isEmpty()) throw new IOException("Address is empty");
        int index = base.indexOf("//");
        if (index < 0) return;
        String prefix = base.substring(0, base.indexOf("/", index + 2) + 1);
        StringBuilder builder = new StringBuilder(prefix);
        for (String part : base.substring(prefix.length()).split("/")) {
            if (part.isEmpty()) continue;
            builder.append(part).append("/");
            String current = builder.toString();
            if (exists(current)) continue;
            createFolder(current);
            if (!exists(current)) throw new IOException("MKCOL failed, folder missing: " + current);
        }
    }

    @Nullable
    public static String get(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).get().build()).execute()) {
            int code = response.code();
            if (code == 404) return null;
            if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
            if (!response.isSuccessful()) throw new IOException("GET failed (" + code + ")");
            if (response.body() == null) return null;
            return response.body().string();
        }
    }

    /**
     * 整份覆盖写同步文件。
     *
     * 有些 WebDAV（实测 43.165.3.81 上那台）PUT 覆盖时**不截断旧内容**：
     * 先传 5008 字节再传 9 字节，读回来还是 5008 字节，前 9 字节是新的、后面全挂着上一版。
     * 客户端拿整串给 Gson 解就直接失败，然后被当成「云端文件不存在」，一轮同步下来数据就没了。
     * 咱们的归档脚本 2026-10-04 就踩过、改成先 DELETE 再 PUT 了，客户端这边现在跟上：
     * 先正常 PUT，回读校验；发现尾部残留才删掉重传，再校验一次。
     * 不直接先删是为了避免 DELETE 成功、PUT 失败时把云端文件弄丢。
     */
    public static void put(String url, String body) throws IOException {
        int code = putWithFolder(url, body);
        requireOk(code);
        String echo = get(url);
        if (same(body, echo)) return;
        // 服务器没截断：删掉带残留的旧文件，重新整份写一次。
        // DELETE 成功、随后 PUT 却失败时云端会整份消失，所以失败要把刚读到的原文尽力放回去，
        // 至少保住上一版内容（带尾巴的也能被 WebDavData.from 截断解析）。
        delete(url);
        code = putWithFolder(url, body);
        if (ok(code)) {
            String again = get(url);
            if (same(body, again)) return;
            restore(url, echo);
            throw new IOException("PUT verify failed (trailing data " + (again == null ? 0 : again.length()) + "/" + body.length() + ")");
        }
        restore(url, echo);
        requireOk(code);
        throw new IOException("PUT verify failed");
    }

    private static boolean ok(int code) {
        return code == 200 || code == 201 || code == 204;
    }

    private static void requireOk(int code) throws IOException {
        if (ok(code)) return;
        if (code == 401 || code == 403) throw new IOException("Auth failed (" + code + ")");
        throw new IOException("PUT failed (" + code + ")");
    }

    /** 补救写：尽力把上一版内容放回去，失败也只能算了，真正的错误由调用方抛 */
    private static void restore(String url, String previous) {
        if (previous == null) return;
        try {
            putWithFolder(url, previous);
        } catch (Exception ignored) {
        }
    }

    private static int putWithFolder(String url, String body) throws IOException {
        int code = putOnce(url, body);
        // 目录被第三方删掉/未建好时，坚果云等会返回 404/409，重建目录后重试一次
        if (code == 404 || code == 409) {
            createFolders();
            code = putOnce(url, body);
        }
        return code;
    }

    /** 覆盖写不截断的服务器要先删旧文件；文件不存在或不许 DELETE 都不算错，随后的 PUT 会报真原因 */
    private static void delete(String url) throws IOException {
        try (Response response = client.newCall(prepare(url).delete().build()).execute()) {
            // 204/200/404/405/403 一律放行
        }
    }

    /** 回读比对：容掉 BOM 和首尾空白，别因为服务器加了点装饰就误判成残留 */
    private static boolean same(String expect, String actual) {
        if (actual == null) return false;
        String text = actual.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        return expect.trim().equals(text);
    }

    private static int putOnce(String url, String body) throws IOException {
        try (Response response = client.newCall(prepare(url).put(RequestBody.create(body, JSON)).build()).execute()) {
            return response.code();
        }
    }

    public static boolean test() {
        return testError() == null;
    }

    /**
     * 连接测试：成功返回 null，失败返回能看懂的原因。
     * 自建 WebDAV（CloudDrive2 等）配置错的时候，光弹一句「连接失败」根本没法排查，
     * 到底是账号密码不对、地址不对还是目录建不出来，得分清楚。
     */
    @Nullable
    public static String testError() {
        try {
            createFolders();
            return null;
        } catch (Exception e) {
            Throwable cause = root(e);
            String msg = cause.getMessage();
            if (msg == null || msg.isEmpty()) msg = cause.getClass().getSimpleName();
            if (msg.startsWith("Auth failed")) return "账号或密码不对（" + msg.replaceAll("[^0-9]", "") + "）";
            if (msg.startsWith("Address is empty")) return "服务地址为空";
            if (msg.startsWith("PROPFIND failed")) return "目录检查失败：" + msg;
            if (msg.startsWith("MKCOL failed")) return "创建目录失败：" + msg;
            return msg;
        }
    }

    public static Throwable root(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
        return cause;
    }

    /**
     * Digest 验证：坚果云这类用「邮箱 + 应用密钥」的 Basic 就能过，
     * 但不少自建 WebDAV（CloudDrive2、群晖、Apache mod_auth_digest）只认 Digest。
     * 逻辑很克制：只有服务器明确回了 401 + WWW-Authenticate: Digest 才补一次，
     * 补过还失败就不再试，绝不会在密码错的时候反复重连。
     */
    private static final class DigestAuth implements Authenticator {

        @Nullable
        @Override
        public Request authenticate(@Nullable Route route, @NonNull Response response) {
            if (response.code() != 401) return null;
            if (!WebDavSetting.hasAuth()) return null;
            // 已经带 Digest 试过一次还没过，别再重试；密码错也不要循环
            String sent = response.request().header("Authorization");
            if (sent != null && sent.regionMatches(true, 0, "Digest ", 0, 7)) return null;
            if (count(response) > 2) return null;
            String challenge = challenge(response.headers().values("WWW-Authenticate"));
            if (challenge == null) return null;
            String header = digest(response.request(), parse(challenge));
            if (header == null) return null;
            return response.request().newBuilder().header("Authorization", header).build();
        }

        /** 顺着 401 链数一下重试了几次，最多只补一次 Digest */
        private int count(Response response) {
            int result = 1;
            while ((response = response.priorResponse()) != null) result++;
            return result;
        }

        /** 一组 WWW-Authenticate 里挑出 Digest 那条（Basic 和 Digest 可能同时出现） */
        @Nullable
        private String challenge(List<String> values) {
            for (String value : values) {
                if (value == null) continue;
                String text = value.trim();
                if (text.regionMatches(true, 0, "Digest ", 0, 7)) return text.substring(7).trim();
            }
            return null;
        }

        /** 拆 challenge 参数：qop="auth,auth-int" 这种带引号的逗号不能当分隔符 */
        private Map<String, String> parse(String body) {
            Map<String, String> map = new HashMap<>();
            List<String> parts = new ArrayList<>();
            StringBuilder part = new StringBuilder();
            boolean quote = false;
            for (char c : body.toCharArray()) {
                if (c == '"') quote = !quote;
                if (c == ',' && !quote) {
                    parts.add(part.toString());
                    part.setLength(0);
                } else {
                    part.append(c);
                }
            }
            parts.add(part.toString());
            for (String item : parts) {
                int eq = item.indexOf('=');
                if (eq < 0) continue;
                String key = item.substring(0, eq).trim().toLowerCase(Locale.US);
                String value = item.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                map.put(key, value);
            }
            return map;
        }

        @Nullable
        private String digest(Request request, Map<String, String> map) {
            String realm = map.get("realm");
            String nonce = map.get("nonce");
            if (realm == null || nonce == null) return null;
            String algorithm = map.containsKey("algorithm") ? map.get("algorithm") : "MD5";
            boolean sess = algorithm.toLowerCase(Locale.US).endsWith("-sess");
            String algo = algorithm.toLowerCase(Locale.US).startsWith("sha-256") ? "SHA-256" : "MD5";
            String qop = "";
            String offered = map.get("qop");
            if (offered != null) {
                for (String item : offered.split(",")) {
                    if ("auth".equalsIgnoreCase(item.trim())) {
                        qop = "auth";
                        break;
                    }
                }
                // 只给 auth-int 的服务不支持（那要把请求体一起算进去），别硬算一个错的
                if (qop.isEmpty()) return null;
            }
            String user = WebDavSetting.getUser();
            String pass = WebDavSetting.getPass();
            String cnonce = hash(algo, System.nanoTime() + ":" + Thread.currentThread().getId() + ":" + request.url());
            if (cnonce == null) return null;
            String ha1 = hash(algo, user + ":" + realm + ":" + pass);
            if (ha1 == null) return null;
            if (sess) ha1 = hash(algo, ha1 + ":" + nonce + ":" + cnonce);
            String uri = request.url().encodedPath();
            if (request.url().encodedQuery() != null) uri = uri + "?" + request.url().encodedQuery();
            String ha2 = hash(algo, request.method() + ":" + uri);
            if (ha1 == null || ha2 == null) return null;
            String nc = "00000001";
            String answer;
            if (qop.isEmpty()) answer = hash(algo, ha1 + ":" + nonce + ":" + ha2);
            else answer = hash(algo, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
            if (answer == null) return null;
            StringBuilder header = new StringBuilder("Digest ");
            header.append("username=\"").append(escape(user)).append("\"");
            header.append(", realm=\"").append(escape(realm)).append("\"");
            header.append(", nonce=\"").append(escape(nonce)).append("\"");
            header.append(", uri=\"").append(escape(uri)).append("\"");
            header.append(", response=\"").append(answer).append("\"");
            header.append(", algorithm=").append(sess ? algo + "-sess" : algo);
            if (!qop.isEmpty()) header.append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
            if (map.containsKey("opaque")) header.append(", opaque=\"").append(escape(map.get("opaque"))).append("\"");
            return header.toString();
        }

        private String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        @Nullable
        private String hash(String algo, String text) {
            try {
                MessageDigest digest = MessageDigest.getInstance(algo);
                byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(bytes.length * 2);
                for (byte b : bytes) {
                    result.append(Character.forDigit((b >> 4) & 0xf, 16));
                    result.append(Character.forDigit(b & 0xf, 16));
                }
                return result.toString();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
