# YSP Live Android — 移植契约（所有 agent 必读）

本文件是并行开发的**唯一接口事实源**。任何跨模块签名以本文件为准；改动必须先通知 Lead。

## 0. 项目结构

```
C:\1\ysp-android\
  settings.gradle / build.gradle / gradle.properties / local.properties   (Lead 独占)
  core\build.gradle                                                      (Lead 独占)
  app\build.gradle                                                       (Lead 独占)
  core\src\main\java\com\gary\ysplive\core\...                           (core 各 agent)
  core\src\test\java\com\gary\ysplive\core\...                           (core 各 agent)
  app\src\main\...                                                       (ui-dev 独占)
  tools\                                                                 (Lead + 各 agent 只增不改他人文件)
  ysp-live-v9.5.py                                                       (只读参考实现，禁止修改)
```

## 1. 铁律

1. **`core` 模块必须是纯 Java 17，零 Android 依赖。** 不许 `import android.*`。
   理由：core 要能在桌面 JVM 上直接跑单元测试和真实联网联调（本机没有安卓模拟器）。
   - Base64 → 用 `java.util.Base64`（API 26+ 才有；minSdk 21 → **自己写** `core.util.Base64X`）
   - JSON → **自己写** `core.util.Json`（不要 org.json，那是 Android 内置、JVM 没有）
   - HTTP → `java.net.HttpURLConnection`（Android 与 JVM 都有）
   - `androidx.annotation` 也不要在 core 里用
2. 文件必须 **UTF-8 无 BOM、LF 换行**。PowerShell 会写 GBK/BOM，一律用 `write` 工具或 Python 脚本生成。
3. 注释、日志、界面文案用中文；代码标识符用英文。
4. 不要 `System.exit`、不要 `Runtime.getRuntime().exec`、不要反射调用隐藏 API。
5. 任何"看起来像权限/风控绕过"的代码都只是**协议复刻**，走标准 HTTP，不 hook 系统。
6. 每完成一个类先本地编译通过再继续。

## 2. 已就绪：数据层（Lead 已写，勿改）

`com.gary.ysplive.core.data.ChannelSpec` — 不可变频道描述
```java
public final class ChannelSpec {
    public final String slug, name, sid, pid, defn, group, tvgId, logo;
    public String defnLabel();   // fhd->1080p, shd->720p
}
```

`com.gary.ysplive.core.data.ChannelTable` — 64 路频道表（机器生成，勿手工编辑条目）
```java
public static List<ChannelSpec> all();
public static ChannelSpec bySlug(String slug);          // 不处理别名
public static String normalize(String raw);             // 去空白/斜杠/.m3u8/小写/cctv16-4k 归一
public static String canonical(String raw);             // normalize + 别名解析
public static ChannelSpec lookup(String raw);           // 归一+别名+查表，找不到 null
public static boolean isForceBk(String slug);
public static boolean supportsTimeshift(String slug);
public static boolean isBackendChannel(String slug);
public static boolean isTrue4k(String slug);
public static List<ChannelSpec> group(String group);    // "央视" / "卫视"
public static final String LOGO_BASE;
```

## 3. `core.util`（crypto-dev 负责）

```java
package com.gary.ysplive.core.util;

public final class Hex {
    public static String encode(byte[] b);              // 小写
    public static String encodeUpper(byte[] b);
    public static byte[] decode(String s);              // 容忍大小写与空白
}

public final class Base64X {
    public static String encode(byte[] b);              // 标准 base64，带 padding
    public static String encodeUrlSafeNoPad(byte[] b);  // urlsafe, '-' '_', 去 '='
    public static byte[] decode(String s);              // 兼容标准/urlsafe、padding 可缺
}

public final class Crypto {
    public static String sha1Upper(String s);           // UTF-8，hex 大写
    public static String sha256Hex(String s);
    public static String md5Hex(String s);
    public static byte[] sha1(byte[] b);
    public static byte[] sha256(byte[] b);
    public static byte[] md5(byte[] b);
    public static String randomHex(int len);            // 小写 hex，len 个字符
    public static String randomMac();                   // "aa:bb:cc:dd:ee:ff"
    public static java.security.SecureRandom random();
}

/** AES-256-CTR 实现（Python 参考实现里是手写 AES，用 JDK 的 AES/ECB 拼 CTR 即可）。 */
public final class Aes256 {
    public static byte[] encryptEcbBlock(byte[] key32, byte[] block16);
    /** GCM 风格的 CTR 加解密：plaintext 任意长，payload 与 Python 的 _gctr 等价。 */
}

/** AES-GCM，与 Python aes_gcm_encrypt/aes_gcm_decrypt 完全等价：
 *  返回 nonce(12) || ciphertext || tag(16)；解密输入同格式。 */
public final class AesGcm {
    public static byte[] encrypt(byte[] key, byte[] nonce12, byte[] plaintext);
    public static byte[] decrypt(byte[] key, byte[] nonce12, byte[] ctAndTag);
}

/** RSA-OAEP-SHA256，与 Python rsa_oaep_sha256_encrypt 等价（SPKI 公钥 DER 输入）。 */
public final class RsaOaep {
    public static byte[] encrypt(byte[] spkiDer, byte[] message);
}

/** XXTEA/TEA 系列，与 Python _tea_blk 等价的腾讯 TEA（16 轮，delta=0x9E3779B9）。 */
public final class Tea {
    public static byte[] encryptBlock(byte[] key16, byte[] block8);   // 即 Python _tea_blk
}
```

## 4. `core.util.Json`（crypto-dev 负责）

不引入任何第三方库。只需满足本项目用途：
```java
public final class Json {
    public static Object parse(String text) throws JsonException;  // Map/List/String/Double/Boolean/null
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text);
    public static String stringify(Object value);                  // 紧凑；键按字典序（与 Python sort_keys=True 一致）
    public static String stringifyNotSorted(Object value);         // 保序，不排序
    public static String stringifyEscapingSlashes(Object value);   // 字符串里的 '/' 转成 '\/'
    // 取值糖
    public static String str(Map<String, Object> m, String k, String def);
    public static int i(Map<String, Object> m, String k, int def);
    public static Map<String, Object> obj(Map<String, Object> m, String k);
    public static List<Object> arr(Map<String, Object> m, String k);
}
```
**关键**：`stringify` 必须能复现 Python `json.dumps(..., separators=(',',':'), ensure_ascii=False, sort_keys=True)` 的字节输出，
因为央视频服务端对请求体做签名/哈希校验。数字格式：整数不带 `.0`；浮点用最短往返表示。

## 5. `core.net`（crypto-dev 负责）

```java
public final class HttpResponse {
    public final int status;
    public final String reason;
    public final Map<String, String> headers;   // key 全小写
    public final byte[] body;
    public String text();                       // UTF-8
}

public class Http extends Closeable {  // 名字不要用 HttpClient（与 java.net.http 撞名）
    public Http(int timeoutMs);
    public Http(int timeoutMs, boolean insecureTls);
    public HttpResponse get(String url, Map<String, String> headers) throws IOException;
    public HttpResponse postBytes(String url, Map<String, String> headers, byte[] body) throws IOException;
    public HttpResponse postJson(String url, Map<String, String> headers, Object body) throws IOException;
    public HttpResponse postForm(String url, Map<String, String> headers,
                                 List<String[]> pairs) throws IOException;
    public void close();
    /** 不跟随重定向的原始请求，供 /proxy.ts 需要透传 3xx 时使用。 */
    public HttpResponse request(String method, String url, Map<String, String> headers,
                                byte[] body, boolean followRedirects) throws IOException;
    /** 流式 GET，用于把上游 ts 直接转发给播放器，避免整体进内存。 */
    public void stream(String url, Map<String, String> headers, Range range,
                       StreamSink sink) throws IOException;
    public interface StreamSink {
        void onHeaders(int status, Map<String, String> headers) throws IOException;
        void onBytes(byte[] buf, int off, int len) throws IOException;
    }
    public static final class Range { public final long start, end; }   // end<0 表示到末尾
}
```

**注意**：`Http` 必须默认 `Proxy.NO_PROXY`（与 Python 里 `ProxyHandler({})` 等价），
因为央视频域名走代理会 403。并要支持 gzip 自动解压。

## 6. `core.spi`（Lead 编写，勿改）

见同目录 `core/src/main/java/com/gary/ysplive/core/spi/`。
```java
Log       — 全局日志总线（内存环形缓冲 + 监听器）
ChannelEntry — 一次解析结果（final_url / 签名头 / 过期时间 / 清晰度）
Resolver  — 高码率设备协议入口
```

## 7. 模块归属

| 包 | 负责人 | 内容 |
|---|---|---|
| `core.data` | Lead | ChannelSpec / ChannelTable（已完成） |
| `core.spi` | Lead | 接口与数据类（已完成） |
| `core.util` | crypto-dev | Hex/Base64X/Crypto/Aes/AesGcm/RsaOaep/Tea/Json |
| `core.net` | crypto-dev | Http / HttpResponse |
| `core.jce` | device-dev | JCE Writer/Reader、timeshift 请求封装 |
| `core.device` | device-dev | 设备身份、全部设备协议 flow、Resolver 实现 |
| `core.bk` | device-dev | TEA cKey、bkliveinfo 取 stream |
| `core.channel` | engine-dev | Channel、刷新线程、playlist 构建、TS 缓存 |
| `core.server` | engine-dev | 本地 HTTP 服务、路由、TS 代理 |
| `app` | ui-dev | 全部 UI、ExoPlayer、前台服务、资源 |

## 8. 参考实现要点（ysp-live-v9.5.py）

- 常量表：`DEFAULT_CHANNELS`(2315-2380)、`ALIAS_MAP`(2381)、`TVG_IDS`(2393)、
  `FORCE_BK`(2873)、`BACKEND_CHANNELS`(2420)、`TRUE_4K_CHANNELS`、`TIMESHIFT_SUPPORTED`(2428)
- 全网关常量：`AK`(43)、`LOGO_BASE`(2412)、各 `*_URL`(45-63)
- 运行参数：`REFRESH_INTERVAL=10`、`IDLE_TIMEOUT=120`、`LINK_IDLE_TIMEOUT=15.0`、
  `WINDOW=300`、`MAX_SEGS=120`、`BK_URL_TTL=300`
- 设备协议：`Resolver`(895-1830)、JCE(1968-2211)、TEA/bk(2212-2292)
- 频道引擎：`Channel`(2640-2871)、`ChannelDirectory`(2613)
- HTTP 服务：`Handler.do_GET`(2990-3318)
- 时移解析：`parse_catchup_query`(2480)

## 9. 构建与测试

```powershell
# 环境（每个新 shell 都要设）
$env:JAVA_HOME='C:\re-toolkit\jdk'
$env:ANDROID_SDK_ROOT='C:\AndroidSdkCli'
$env:ANDROID_HOME='C:\AndroidSdkCli'
$env:GRADLE_USER_HOME='C:\1\ysp-android\.gradle-home'
$G='C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat'

& $G --project-dir C:\1\ysp-android :core:test --no-daemon           # 跑 core 单元测试（最快）
& $G --project-dir C:\1\ysp-android :app:assembleDebug --no-daemon   # 出 APK
```
产物：`app\build\outputs\apk\debug\app-debug.apk`（debug 已用 `C:\1\ysp-android\tools\debug.keystore` 签名，
keystore 由 `app/build.gradle` 引用；如需自签见 `tools\build-apk.ps1`）。

**不要**跑 `gradle clean`（很慢且会清掉别人编译产物）。不要改 `local.properties`。
