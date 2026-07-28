package com.openai.m3u8zipmp4;

import android.app.*;
import android.content.*;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ConverterService extends Service {
    public static final String ACTION_START = "com.openai.m3u8zipmp4.START";
    public static final String ACTION_CANCEL = "com.openai.m3u8zipmp4.CANCEL";
    public static final String ACTION_PROGRESS = "com.openai.m3u8zipmp4.PROGRESS";
    public static final String ACTION_DONE = "com.openai.m3u8zipmp4.DONE";

    private static final String CHANNEL = "video_convert";
    private static final int NOTIFICATION_ID = 1308;
    private static final long MAX_UNZIPPED_BYTES = 30L * 1024 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20000;
    private static final byte[] BUILTIN_INIT_720X1280 = Base64.getDecoder().decode(
            "AAAAHGZ0eXBpc281AAACAGlzbzVpc282bXA0MQAAAwFtb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAAAAABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACBHRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAC0AAABQAAAAAAAaBtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAiVRAAAAAAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAFLbWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABC3N0YmwAAAC/c3RzZAAAAAAAAAABAAAAr2F2YzEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAC0AUAAEgAAABIAAAAAAAAAAEVTGF2YzYxLjE5LjEwMSBsaWJ4MjY0AAAAAAAAAAAAAAAY//8AAAA1YXZjQwFkAB//4QAaZ2QAH6zZgLQKGwEQAAADABAAAAMDwPGDGaABAARo6XvL/fj4AAAAABBwYXNwAAAAAQAAAAEAAAAUYnRydAAD6AAAH0AAAAAAAAAAABBzdHRzAAAAAAAAAAAAAAAQc3RzYwAAAAAAAAAAAAAAFHN0c3oAAAAAAAAAAAAAAAAAAAAQc3RjbwAAAAAAAAAAAAAAKG12ZXgAAAAgdHJleAAAAAAAAAABAAAAAQAAAAAAAAAAAAAAAAAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjEuNy4xMDA=");

    private volatile boolean cancelled;
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;
    private String referer = "", cookie = "", userAgent = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            updateProgress(-1, "正在取消…");
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || worker != null) return START_NOT_STICKY;

        cancelled = false;
        String mode = nvl(intent.getStringExtra("mode"));
        String requestedName = nvl(intent.getStringExtra("name"));
        referer = nvl(intent.getStringExtra("referer"));
        cookie = nvl(intent.getStringExtra("cookie"));
        userAgent = nvl(intent.getStringExtra("ua"));
        if (userAgent.isEmpty()) userAgent = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36";
        String url = nvl(intent.getStringExtra("url"));
        String zipUri = nvl(intent.getStringExtra("zipUri"));
        String zipName = nvl(intent.getStringExtra("zipName"));

        startForeground(NOTIFICATION_ID, makeNotification("正在准备…", 0, true, true));
        worker = new Thread(() -> {
            if ("zip".equals(mode)) runZipJob(zipUri, zipName, requestedName);
            else runUrlJob(url, requestedName);
        }, "video-converter");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runUrlJob(String url, String requestedName) {
        File workDir = new File(getCacheDir(), "url_" + System.currentTimeMillis());
        File combined = new File(workDir, "combined.bin");
        File output = new File(workDir, "output.mp4");
        workDir.mkdirs();
        try {
            acquireWakeLock();
            updateProgress(-1, "正在读取播放列表…");
            MediaPlaylist playlist = loadRemotePlaylist(url, 0);
            checkCancelled();
            if (!playlist.endList) throw new IOException("检测到直播播放列表。当前版本仅支持带 #EXT-X-ENDLIST 的点播视频。");
            if (playlist.segments.isEmpty()) throw new IOException("播放列表中没有找到视频分片。");
            if (playlist.hasByteRange) throw new IOException("当前版本暂不支持 #EXT-X-BYTERANGE 分片。");

            updateProgress(3, "已找到 " + playlist.segments.size() + " 个分片");
            Boolean fmp4 = null;
            Map<String, byte[]> keyCache = new HashMap<>();
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(combined), 1024 * 1024)) {
                if (playlist.initUrl != null) out.write(downloadWithRetry(playlist.initUrl, 3));
                for (int i = 0; i < playlist.segments.size(); i++) {
                    checkCancelled();
                    Segment seg = playlist.segments.get(i);
                    byte[] data = downloadWithRetry(seg.url, 3);
                    if (seg.key != null) data = decryptRemote(data, seg.key, playlist.mediaSequence + i, keyCache);
                    if (fmp4 == null) fmp4 = detectFragmentType(data);
                    out.write(data);
                    int p = 4 + (int) (((i + 1L) * 76L) / playlist.segments.size());
                    updateProgress(p, "正在下载分片 " + (i + 1) + "/" + playlist.segments.size());
                }
            }
            checkCancelled();
            if (Boolean.TRUE.equals(fmp4)) {
                if (playlist.initUrl == null && !fileContainsBox(combined, "moov"))
                    throw new IOException("分段 MP4 缺少 #EXT-X-MAP 初始化片段，无法生成可播放 MP4。");
                updateProgress(84, "正在整理分段 MP4…");
                copyFile(combined, output);
            } else {
                updateProgress(82, "正在无损封装为 MP4…");
                remuxTsToMp4(combined, output);
            }
            finishSuccess(output, requestedName, "M3U8");
        } catch (CancelledException e) {
            finishCancelled();
        } catch (Exception e) {
            finishFailure(e);
        } finally {
            cleanup(workDir);
        }
    }

    private void runZipJob(String uriText, String zipDisplayName, String requestedName) {
        File workDir = new File(getCacheDir(), "zip_" + System.currentTimeMillis());
        File extracted = new File(workDir, "files");
        File combined = new File(workDir, "combined.bin");
        File output = new File(workDir, "output.mp4");
        workDir.mkdirs(); extracted.mkdirs();
        try {
            acquireWakeLock();
            if (uriText.isEmpty()) throw new IOException("没有收到 ZIP 文件地址。");
            updateProgress(-1, "正在解压 ZIP…");
            LinkedHashMap<String, File> files = unzip(Uri.parse(uriText), extracted);
            checkCancelled();
            if (files.isEmpty()) throw new IOException("ZIP 中没有可用文件。");
            updateProgress(24, "已解压 " + files.size() + " 个文件，正在识别播放列表…");

            LocalPlaylist playlist = chooseLocalPlaylist(files);
            List<LocalSegment> segments;
            File initFile = null;
            long mediaSequence = 0;
            String playlistText = "";
            if (playlist != null) {
                if (playlist.hasByteRange) throw new IOException("ZIP 内播放列表使用 #EXT-X-BYTERANGE，当前版本暂不支持。");
                segments = playlist.segments;
                initFile = playlist.initFile;
                mediaSequence = playlist.mediaSequence;
                playlistText = playlist.text;
            } else {
                segments = fallbackSegments(files);
            }
            if (segments.isEmpty()) throw new IOException("没有找到可合并的 TS、M4S 或分段 MP4 文件。");
            updateProgress(30, "已找到 " + segments.size() + " 个分片");

            Boolean fmp4 = null;
            byte[] firstPlain = null;
            Map<String, byte[]> localKeyCache = new HashMap<>();
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(combined), 1024 * 1024)) {
                for (int i = 0; i < segments.size(); i++) {
                    checkCancelled();
                    LocalSegment seg = segments.get(i);
                    byte[] data = readFile(seg.file);
                    if (seg.key != null) data = decryptLocal(data, seg.key, mediaSequence + i, localKeyCache);
                    if (firstPlain == null) firstPlain = data;
                    if (fmp4 == null) fmp4 = detectFragmentType(data);
                    out.write(data);
                    int p = 30 + (int) (((i + 1L) * 48L) / segments.size());
                    updateProgress(p, "正在读取分片 " + (i + 1) + "/" + segments.size());
                }
            }
            checkCancelled();

            if (Boolean.TRUE.equals(fmp4)) {
                updateProgress(80, "正在整理分段 MP4…");
                byte[] init;
                if (initFile != null) {
                    init = readFile(initFile);
                } else {
                    initFile = findAnyInitFile(files, segments);
                    if (initFile != null) init = readFile(initFile);
                    else {
                        Resolution resolution = parseResolution(playlistText + "\n" + joinNames(segments));
                        if (resolution != null && !(resolution.width == 720 && resolution.height == 1280)) {
                            throw new IOException("分段 MP4 缺少初始化片段；内置修复头仅适配 720×1280，检测到 " + resolution.width + "×" + resolution.height + "。");
                        }
                        init = Arrays.copyOf(BUILTIN_INIT_720X1280, BUILTIN_INIT_720X1280.length);
                        if (firstPlain != null) {
                            Long timescale = inferTimescale(firstPlain, firstDuration(playlist));
                            if (timescale != null) patchMdhdTimescale(init, timescale);
                        }
                    }
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output), 1024 * 1024)) {
                    out.write(init);
                    try (InputStream in = new BufferedInputStream(new FileInputStream(combined), 1024 * 1024)) { copy(in, out); }
                }
            } else {
                updateProgress(80, "正在无损封装 TS 为 MP4…");
                remuxTsToMp4(combined, output);
            }
            String defaultStem = stripExtension(zipDisplayName.isEmpty() ? "ZIP视频" : zipDisplayName);
            finishSuccess(output, requestedName.isEmpty() ? defaultStem : requestedName, "ZIP");
        } catch (CancelledException e) {
            finishCancelled();
        } catch (Exception e) {
            finishFailure(e);
        } finally {
            cleanup(workDir);
        }
    }

    private LinkedHashMap<String, File> unzip(Uri uri, File root) throws Exception {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        String rootPath = root.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("无法打开 ZIP 文件。");
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1024 * 1024))) {
                ZipEntry entry;
                byte[] buf = new byte[1024 * 1024];
                while ((entry = zin.getNextEntry()) != null) {
                    checkCancelled();
                    entries++;
                    if (entries > MAX_ZIP_ENTRIES) throw new IOException("ZIP 文件数量过多。");
                    String normalized = normalizeZipPath(entry.getName());
                    if (normalized.isEmpty() || entry.isDirectory()) { zin.closeEntry(); continue; }
                    File target = new File(root, normalized);
                    String canonical = target.getCanonicalPath();
                    if (!canonical.startsWith(rootPath)) throw new IOException("ZIP 包含不安全路径：" + entry.getName());
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建临时目录。");
                    try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(target), 1024 * 1024)) {
                        int n;
                        while ((n = zin.read(buf)) >= 0) {
                            checkCancelled();
                            total += n;
                            if (total > MAX_UNZIPPED_BYTES) throw new IOException("解压后文件超过 30 GB 安全限制。");
                            fout.write(buf, 0, n);
                        }
                    }
                    out.put(normalized, target);
                    if (entries % 10 == 0) updateProgress(-1, "正在解压 ZIP：已处理 " + entries + " 个项目");
                    zin.closeEntry();
                }
            }
        }
        return out;
    }

    private LocalPlaylist chooseLocalPlaylist(LinkedHashMap<String, File> files) throws Exception {
        List<String> m3u8s = new ArrayList<>();
        for (String path : files.keySet()) if (path.toLowerCase(Locale.US).endsWith(".m3u8")) m3u8s.add(path);
        if (m3u8s.isEmpty()) return null;

        LocalPlaylist best = null;
        for (String path : m3u8s) {
            try {
                LocalPlaylist p = loadLocalPlaylist(path, files, 0);
                if (p != null && !p.segments.isEmpty() && (best == null || p.segments.size() > best.segments.size())) best = p;
            } catch (IOException e) {
                if (best == null && m3u8s.size() == 1) throw e;
            }
        }
        return best;
    }

    private LocalPlaylist loadLocalPlaylist(String playlistPath, LinkedHashMap<String, File> files, int depth) throws Exception {
        if (depth > 5) throw new IOException("ZIP 内播放列表嵌套层级过深。");
        File playlistFile = files.get(playlistPath);
        if (playlistFile == null) throw new IOException("找不到播放列表：" + playlistPath);
        String text = new String(readFile(playlistFile), StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (!text.contains("#EXTM3U")) throw new IOException("ZIP 内的 m3u8 格式无效。");
        String[] lines = text.replace("\r", "").split("\n");
        List<LocalVariant> variants = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                long bandwidth = parseLongAttr(line, "BANDWIDTH", 0);
                int j = i + 1;
                while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++;
                if (j < lines.length) {
                    FileRef ref = resolveLocalRef(playlistPath, lines[j].trim(), files, true);
                    if (ref != null) variants.add(new LocalVariant(ref.path, bandwidth));
                }
            }
        }
        if (!variants.isEmpty()) {
            variants.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
            return loadLocalPlaylist(variants.get(0).path, files, depth + 1);
        }

        LocalPlaylist result = new LocalPlaylist();
        result.path = playlistPath;
        result.text = text;
        result.endList = text.contains("#EXT-X-ENDLIST");
        LocalKeySpec currentKey = null;
        double duration = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { result.mediaSequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
            } else if (line.startsWith("#EXT-X-MAP:")) {
                String u = parseStringAttr(line, "URI");
                FileRef ref = resolveLocalRef(playlistPath, u, files, false);
                if (ref != null) result.initFile = ref.file;
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = parseStringAttr(line, "METHOD");
                if (method.isEmpty()) method = parseBareAttr(line, "METHOD");
                if ("NONE".equalsIgnoreCase(method)) currentKey = null;
                else if ("AES-128".equalsIgnoreCase(method)) {
                    String u = parseStringAttr(line, "URI");
                    FileRef ref = resolveLocalRef(playlistPath, u, files, false);
                    if (ref == null) throw new IOException("ZIP 内找不到 AES-128 密钥文件：" + u);
                    currentKey = new LocalKeySpec(ref.file, parseBareAttr(line, "IV"));
                } else if (!method.isEmpty()) {
                    throw new IOException("不支持的加密方式：" + method + "。SAMPLE-AES/DRM 无法转换。");
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                result.hasByteRange = true;
            } else if (line.startsWith("#EXTINF:")) {
                String n = line.substring(line.indexOf(':') + 1).split(",")[0];
                try { duration = Double.parseDouble(n); } catch (Exception ignored) { duration = 0; }
            } else if (!line.startsWith("#")) {
                FileRef ref = resolveLocalRef(playlistPath, line, files, false);
                if (ref == null) throw new IOException("ZIP 内找不到播放列表引用的分片：" + line);
                result.segments.add(new LocalSegment(ref.path, ref.file, duration, currentKey == null ? null : currentKey.copy()));
                duration = 0;
            }
        }
        return result;
    }

    private FileRef resolveLocalRef(String playlistPath, String reference, LinkedHashMap<String, File> files, boolean onlyM3u8) {
        if (reference == null || reference.trim().isEmpty()) return null;
        String ref = reference.trim();
        try {
            URI u = URI.create(ref);
            if (u.getPath() != null && !u.getPath().isEmpty()) ref = u.getPath();
        } catch (Exception ignored) {}
        while (ref.startsWith("/")) ref = ref.substring(1);
        String parent = "";
        int slash = playlistPath.lastIndexOf('/');
        if (slash >= 0) parent = playlistPath.substring(0, slash + 1);
        String candidate = normalizeRelativePath(parent + ref);
        File f = files.get(candidate);
        if (f != null && (!onlyM3u8 || candidate.toLowerCase(Locale.US).endsWith(".m3u8"))) return new FileRef(candidate, f);

        String basename = basename(ref);
        FileRef match = null;
        for (Map.Entry<String, File> e : files.entrySet()) {
            if (basename(e.getKey()).equalsIgnoreCase(basename)) {
                if (onlyM3u8 && !e.getKey().toLowerCase(Locale.US).endsWith(".m3u8")) continue;
                if (match != null) return null; // ambiguous basename
                match = new FileRef(e.getKey(), e.getValue());
            }
        }
        return match;
    }

    private List<LocalSegment> fallbackSegments(LinkedHashMap<String, File> files) throws Exception {
        List<LocalSegment> out = new ArrayList<>();
        for (Map.Entry<String, File> e : files.entrySet()) {
            String p = e.getKey().toLowerCase(Locale.US);
            if (!(p.endsWith(".ts") || p.endsWith(".m4s") || p.endsWith(".cmfv") || p.endsWith(".mp4"))) continue;
            byte[] head = readHead(e.getValue(), 1024 * 1024);
            if (containsBox(head, "moov") && !containsBox(head, "moof")) continue; // init file
            out.add(new LocalSegment(e.getKey(), e.getValue(), 0, null));
        }
        out.sort((a, b) -> naturalCompare(a.path, b.path));
        return out;
    }

    private File findAnyInitFile(LinkedHashMap<String, File> files, List<LocalSegment> segments) throws Exception {
        Set<File> segmentFiles = new HashSet<>();
        for (LocalSegment s : segments) segmentFiles.add(s.file);
        for (Map.Entry<String, File> e : files.entrySet()) {
            if (segmentFiles.contains(e.getValue())) continue;
            String p = e.getKey().toLowerCase(Locale.US);
            if (!(p.endsWith(".mp4") || p.endsWith(".m4s") || p.endsWith(".ts") || p.endsWith(".cmfv"))) continue;
            byte[] head = readHead(e.getValue(), 1024 * 1024);
            if (containsBox(head, "moov")) return e.getValue();
        }
        return null;
    }

    private MediaPlaylist loadRemotePlaylist(String url, int depth) throws Exception {
        if (depth > 5) throw new IOException("播放列表嵌套层级过深。");
        String text = new String(downloadWithRetry(url, 3), StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (!text.contains("#EXTM3U")) throw new IOException("地址返回的内容不是有效 m3u8。");
        String[] lines = text.replace("\r", "").split("\n");
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                long bandwidth = parseLongAttr(line, "BANDWIDTH", 0);
                String resolution = parseStringAttr(line, "RESOLUTION");
                int j = i + 1;
                while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++;
                if (j < lines.length) variants.add(new Variant(resolve(url, lines[j].trim()), bandwidth, resolution));
            }
        }
        if (!variants.isEmpty()) {
            variants.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
            Variant chosen = variants.get(0);
            updateProgress(-1, "检测到主播放列表，选择最高码率" + (chosen.resolution.isEmpty() ? "" : "（" + chosen.resolution + "）") + "…");
            return loadRemotePlaylist(chosen.url, depth + 1);
        }

        MediaPlaylist result = new MediaPlaylist();
        result.sourceUrl = url;
        result.endList = text.contains("#EXT-X-ENDLIST");
        KeySpec currentKey = null;
        double duration = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { result.mediaSequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
            } else if (line.startsWith("#EXT-X-MAP:")) {
                String uri = parseStringAttr(line, "URI");
                if (!uri.isEmpty()) result.initUrl = resolve(url, uri);
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String method = parseStringAttr(line, "METHOD");
                if (method.isEmpty()) method = parseBareAttr(line, "METHOD");
                if ("NONE".equalsIgnoreCase(method)) currentKey = null;
                else if ("AES-128".equalsIgnoreCase(method)) {
                    String uri = parseStringAttr(line, "URI");
                    if (uri.isEmpty()) throw new IOException("AES-128 密钥缺少 URI。");
                    currentKey = new KeySpec(resolve(url, uri), parseBareAttr(line, "IV"));
                } else if (!method.isEmpty()) {
                    throw new IOException("不支持的加密方式：" + method + "。SAMPLE-AES/DRM 无法转换。");
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                result.hasByteRange = true;
            } else if (line.startsWith("#EXTINF:")) {
                String n = line.substring(line.indexOf(':') + 1).split(",")[0];
                try { duration = Double.parseDouble(n); } catch (Exception ignored) { duration = 0; }
            } else if (!line.startsWith("#")) {
                result.segments.add(new Segment(resolve(url, line), duration, currentKey == null ? null : currentKey.copy()));
                duration = 0;
            }
        }
        return result;
    }

    private byte[] decryptRemote(byte[] encrypted, KeySpec spec, long sequence, Map<String, byte[]> cache) throws Exception {
        byte[] key = cache.get(spec.url);
        if (key == null) {
            key = downloadWithRetry(spec.url, 3);
            if (key.length != 16) throw new IOException("AES-128 密钥长度不是 16 字节。");
            cache.put(spec.url, key);
        }
        return aesDecrypt(encrypted, key, spec.iv, sequence);
    }

    private byte[] decryptLocal(byte[] encrypted, LocalKeySpec spec, long sequence, Map<String, byte[]> cache) throws Exception {
        String keyId = spec.file.getAbsolutePath();
        byte[] key = cache.get(keyId);
        if (key == null) {
            key = readFile(spec.file);
            if (key.length != 16) throw new IOException("ZIP 内 AES-128 密钥长度不是 16 字节。");
            cache.put(keyId, key);
        }
        return aesDecrypt(encrypted, key, spec.iv, sequence);
    }

    private byte[] aesDecrypt(byte[] encrypted, byte[] key, String ivText, long sequence) throws Exception {
        byte[] iv = ivText == null || ivText.isEmpty() ? sequenceIv(sequence) : parseIv(ivText);
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (javax.crypto.BadPaddingException e) {
            if (encrypted.length % 16 != 0) throw e;
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        }
    }

    private Boolean detectFragmentType(byte[] data) throws IOException {
        if (looksLikeTs(data)) return false;
        if (looksLikeMp4(data)) return true;
        throw new IOException("无法识别分片格式：既不像 MPEG-TS，也不像分段 MP4。");
    }

    private void remuxTsToMp4(File input, File output) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) throw new IOException("系统无法解析这些 TS 分片。");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> trackMap = new HashMap<>();
            Map<Integer, Long> lastPts = new HashMap<>();
            int maxInput = 1024 * 1024;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    int outTrack = muxer.addTrack(format);
                    trackMap.put(i, outTrack);
                    extractor.selectTrack(i);
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = Math.max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            if (trackMap.isEmpty()) throw new IOException("没有找到可封装的视频或音频轨道。");
            muxer.start(); muxerStarted = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(Math.max(maxInput, 1024 * 1024), 16 * 1024 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                checkCancelled();
                int inTrack = extractor.getSampleTrackIndex();
                if (inTrack < 0) break;
                Integer outTrack = trackMap.get(inTrack);
                if (outTrack == null) { extractor.advance(); continue; }
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long pts = extractor.getSampleTime();
                Long last = lastPts.get(inTrack);
                if (pts < 0) pts = last == null ? 0 : last + 1;
                if (last != null && pts <= last) pts = last + 1;
                lastPts.put(inTrack, pts);
                info.set(0, size, pts, extractor.getSampleFlags());
                muxer.writeSampleData(outTrack, buffer, info);
                extractor.advance();
            }
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try { if (muxerStarted) muxer.stop(); }
                catch (Exception e) { if (output.exists()) output.delete(); throw e; }
                finally { try { muxer.release(); } catch (Exception ignored) {} }
            }
        }
        if (!output.exists() || output.length() < 1024) throw new IOException("MP4 封装失败。");
    }

    private void finishSuccess(File output, String requestedName, String sourceLabel) throws Exception {
        checkCancelled();
        updateProgress(94, "正在保存到手机电影目录…");
        String filename = makeFilename(requestedName, sourceLabel);
        Uri saved = saveToMovies(output, filename);
        String message = "转换完成：" + filename + "\n已保存到 Movies/M3U8转MP4";
        updateProgress(100, message);
        sendDone(true, message, saved == null ? "" : saved.toString());
        finishNotification("转换完成：" + filename, true);
    }

    private void finishCancelled() {
        sendDone(false, "任务已取消。", "");
        finishNotification("任务已取消", false);
    }

    private void finishFailure(Exception e) {
        String message = friendlyMessage(e);
        sendDone(false, "转换失败：" + message, "");
        finishNotification("转换失败：" + message, false);
    }

    private void cleanup(File workDir) {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        deleteRecursive(workDir);
        worker = null;
        stopForeground(false);
        stopSelf();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "M3U8ZipMP4:convert");
        wakeLock.acquire(6L * 60 * 60 * 1000);
    }

    private Uri saveToMovies(File source, String filename) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/M3U8转MP4");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) throw new IOException("无法创建输出文件。");
            try (InputStream in = new BufferedInputStream(new FileInputStream(source)); OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(uri), 1024 * 1024)) {
                if (out == null) throw new IOException("无法打开输出文件。");
                copy(in, out);
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "M3U8转MP4");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建电影目录。");
        File target = uniqueFile(dir, filename);
        copyFile(source, target);
        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)));
        return Uri.fromFile(target);
    }

    private byte[] downloadWithRetry(String url, int attempts) throws Exception {
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            checkCancelled();
            try { return download(url); }
            catch (Exception e) { last = e; if (i < attempts) Thread.sleep(800L * i); }
        }
        throw last == null ? new IOException("下载失败") : last;
    }

    private byte[] download(String urlString) throws Exception {
        URL url = new URL(urlString);
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (!referer.isEmpty()) c.setRequestProperty("Referer", referer);
            if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location"); c.disconnect();
                if (loc == null) throw new IOException("重定向缺少 Location。");
                url = new URL(url, loc); continue;
            }
            if (code < 200 || code >= 300) {
                String msg = "HTTP " + code;
                c.disconnect();
                if (code == 401 || code == 403) msg += "（可能需要 Referer 或 Cookie）";
                throw new IOException(msg);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) >= 0) { checkCancelled(); out.write(buf, 0, n); }
                return out.toByteArray();
            } finally { c.disconnect(); }
        }
        throw new IOException("重定向次数过多。");
    }

    private void updateProgress(int value, String message) {
        Intent i = new Intent(ACTION_PROGRESS); i.setPackage(getPackageName()); i.putExtra("progress", value); i.putExtra("message", message); sendBroadcast(i);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, makeNotification(message, Math.max(0, value), value < 0, true));
    }

    private void sendDone(boolean ok, String message, String uri) {
        Intent i = new Intent(ACTION_DONE); i.setPackage(getPackageName()); i.putExtra("ok", ok); i.putExtra("message", message); i.putExtra("uri", uri); sendBroadcast(i);
    }

    private Notification makeNotification(String text, int progress, boolean indeterminate, boolean ongoing) {
        Intent cancel = new Intent(this, ConverterService.class); cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 2, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("M3U8 / ZIP 转 MP4").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true).setOngoing(ongoing).setProgress(100, progress, indeterminate);
        if (ongoing) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi);
        return b.build();
    }

    private void finishNotification(String text, boolean ok) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("M3U8 / ZIP 转 MP4").setContentText(text).setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error).setAutoCancel(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, b.build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "视频下载与转换", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("显示 M3U8 下载、ZIP 解压和 MP4 转换进度");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private static long firstDuration(LocalPlaylist p) { return p == null || p.segments.isEmpty() ? 0 : Math.round(p.segments.get(0).duration * 1000000.0); }

    private static Long inferTimescale(byte[] segment, long durationUs) {
        try {
            Box moof = findBox(segment, 0, segment.length, "moof");
            Box traf = moof == null ? null : findBox(segment, moof.payloadStart(), moof.end(), "traf");
            Box tfhd = traf == null ? null : findBox(segment, traf.payloadStart(), traf.end(), "tfhd");
            Box trun = traf == null ? null : findBox(segment, traf.payloadStart(), traf.end(), "trun");
            if (tfhd == null || trun == null || durationUs <= 0) return null;
            int p = tfhd.payloadStart();
            int tfFlags = ((segment[p + 1] & 255) << 16) | ((segment[p + 2] & 255) << 8) | (segment[p + 3] & 255);
            p += 8;
            if ((tfFlags & 0x1) != 0) p += 8;
            if ((tfFlags & 0x2) != 0) p += 4;
            long defaultDur = 0;
            if ((tfFlags & 0x8) != 0) { defaultDur = be32(segment, p); p += 4; }
            if ((tfFlags & 0x10) != 0) p += 4;
            if ((tfFlags & 0x20) != 0) p += 4;

            p = trun.payloadStart();
            int trFlags = ((segment[p + 1] & 255) << 16) | ((segment[p + 2] & 255) << 8) | (segment[p + 3] & 255);
            p += 4;
            long count = be32(segment, p); p += 4;
            if ((trFlags & 0x1) != 0) p += 4;
            if ((trFlags & 0x4) != 0) p += 4;
            long units = 0;
            for (int i = 0; i < count; i++) {
                if ((trFlags & 0x100) != 0) { units += be32(segment, p); p += 4; } else units += defaultDur;
                if ((trFlags & 0x200) != 0) p += 4;
                if ((trFlags & 0x400) != 0) p += 4;
                if ((trFlags & 0x800) != 0) p += 4;
            }
            if (units > 0) {
                long ts = Math.round(units * 1000000.0 / durationUs);
                if (ts >= 1000 && ts <= 100000000) return ts;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void patchMdhdTimescale(byte[] init, long timescale) {
        for (int i = 4; i + 28 < init.length; i++) {
            if (ascii4(init, i).equals("mdhd")) {
                int version = init[i + 4] & 255;
                int offset = version == 1 ? i + 24 : i + 16;
                if (offset + 4 <= init.length) writeBe32(init, offset, timescale);
                return;
            }
        }
    }

    private static Box findBox(byte[] b, int start, int end, String wanted) {
        int p = start;
        while (p + 8 <= end) {
            long size = be32(b, p); int header = 8;
            if (size == 1) { if (p + 16 > end) return null; size = (be32(b, p + 8) << 32) | be32(b, p + 12); header = 16; }
            else if (size == 0) size = end - p;
            if (size < header || size > Integer.MAX_VALUE || p + size > end) return null;
            String type = ascii4(b, p + 4);
            Box box = new Box(p, (int) size, header);
            if (type.equals(wanted)) return box;
            p += (int) size;
        }
        return null;
    }

    private static Resolution parseResolution(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?:^|\\D)(\\d{3,4})[xX](\\d{3,4})(?:\\D|$)").matcher(text);
        Resolution last = null;
        while (m.find()) last = new Resolution(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return last;
    }

    private static String joinNames(List<LocalSegment> list) {
        StringBuilder s = new StringBuilder();
        for (LocalSegment x : list) s.append(x.path).append('\n');
        return s.toString();
    }

    private static int naturalCompare(String a, String b) {
        Matcher ma = Pattern.compile("(\\d+)|(\\D+)").matcher(a.toLowerCase(Locale.US));
        Matcher mb = Pattern.compile("(\\d+)|(\\D+)").matcher(b.toLowerCase(Locale.US));
        while (ma.find() && mb.find()) {
            String sa = ma.group(), sb = mb.group();
            boolean na = Character.isDigit(sa.charAt(0)), nb = Character.isDigit(sb.charAt(0));
            int cmp;
            if (na && nb) {
                String ta = sa.replaceFirst("^0+(?!$)", ""), tb = sb.replaceFirst("^0+(?!$)", "");
                cmp = Integer.compare(ta.length(), tb.length());
                if (cmp == 0) cmp = ta.compareTo(tb);
            } else cmp = sa.compareTo(sb);
            if (cmp != 0) return cmp;
        }
        return a.compareToIgnoreCase(b);
    }

    private static String normalizeZipPath(String p) throws IOException {
        if (p == null) return "";
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        String normalized = normalizeRelativePath(p);
        if (normalized.startsWith("../") || normalized.equals("..")) throw new IOException("ZIP 包含不安全路径。");
        return normalized;
    }

    private static String normalizeRelativePath(String p) {
        Deque<String> stack = new ArrayDeque<>();
        for (String part : p.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!stack.isEmpty()) stack.removeLast(); else stack.addLast(".."); }
            else stack.addLast(part);
        }
        return String.join("/", stack);
    }

    private static String basename(String p) { p = p.replace('\\', '/'); int i = p.lastIndexOf('/'); return i >= 0 ? p.substring(i + 1) : p; }
    private static String stripExtension(String s) { if (s == null) return ""; int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\')); int dot = s.lastIndexOf('.'); return dot > slash ? s.substring(0, dot) : s; }
    private static String resolve(String base, String relative) throws MalformedURLException { return new URL(new URL(base), relative).toString(); }
    private static String nvl(String s) { return s == null ? "" : s; }
    private static String parseStringAttr(String line, String key) { Matcher m = Pattern.compile("(?:^|[,\\:])" + Pattern.quote(key) + "=\\\"([^\\\"]*)\\\"").matcher(line); return m.find() ? m.group(1) : ""; }
    private static String parseBareAttr(String line, String key) { Matcher m = Pattern.compile("(?:^|[,\\:])" + Pattern.quote(key) + "=([^,]*)").matcher(line); return m.find() ? m.group(1).trim().replace("\"", "") : ""; }
    private static long parseLongAttr(String line, String key, long fallback) { try { return Long.parseLong(parseBareAttr(line, key)); } catch (Exception e) { return fallback; } }
    private static byte[] sequenceIv(long n) { byte[] iv = new byte[16]; for (int i = 15; i >= 0; i--) { iv[i] = (byte) (n & 0xff); n >>>= 8; } return iv; }
    private static byte[] parseIv(String s) throws IOException { s = s.trim(); if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2); if (s.length() > 32) throw new IOException("AES IV 长度异常。"); while (s.length() < 32) s = "0" + s; byte[] out = new byte[16]; try { for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16); } catch (Exception e) { throw new IOException("AES IV 格式错误。"); } return out; }
    private static boolean looksLikeTs(byte[] b) { int limit = Math.min(b.length - 376, 2048); for (int i = 0; i <= limit; i++) if ((b[i] & 255) == 0x47 && (b[i + 188] & 255) == 0x47 && (b[i + 376] & 255) == 0x47) return true; return b.length > 0 && (b[0] & 255) == 0x47; }
    private static boolean looksLikeMp4(byte[] b) { return containsBox(b, "ftyp") || containsBox(b, "styp") || containsBox(b, "moof") || containsBox(b, "moov"); }
    private static boolean containsBox(byte[] b, String type) { byte[] t = type.getBytes(StandardCharsets.US_ASCII); int max = Math.min(b.length - 4, 1024 * 1024); for (int i = 4; i <= max; i++) if (b[i] == t[0] && b[i+1] == t[1] && b[i+2] == t[2] && b[i+3] == t[3]) return true; return false; }
    private static boolean fileContainsBox(File f, String type) throws IOException { return containsBox(readHead(f, 1024 * 1024), type); }
    private static long be32(byte[] b, int p) { return ((long)(b[p] & 255) << 24) | ((long)(b[p+1] & 255) << 16) | ((long)(b[p+2] & 255) << 8) | (b[p+3] & 255L); }
    private static void writeBe32(byte[] b, int p, long n) { b[p]=(byte)(n>>>24); b[p+1]=(byte)(n>>>16); b[p+2]=(byte)(n>>>8); b[p+3]=(byte)n; }
    private static String ascii4(byte[] b, int p) { return new String(b, p, 4, StandardCharsets.US_ASCII); }
    private static byte[] readFile(File f) throws IOException { try (InputStream in = new BufferedInputStream(new FileInputStream(f)); ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(f.length(), 8L*1024*1024))) { copy(in, out); return out.toByteArray(); } }
    private static byte[] readHead(File f, int limit) throws IOException { int n = (int)Math.min(f.length(), limit); byte[] b = new byte[n]; try (InputStream in = new FileInputStream(f)) { int p=0,r; while(p<n && (r=in.read(b,p,n-p))>0)p+=r; return p==n?b:Arrays.copyOf(b,p); } }
    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] buf = new byte[1024 * 1024]; int n; while ((n = in.read(buf)) >= 0) out.write(buf, 0, n); }
    private static void copyFile(File a, File b) throws IOException { try (InputStream in = new BufferedInputStream(new FileInputStream(a)); OutputStream out = new BufferedOutputStream(new FileOutputStream(b))) { copy(in, out); } }
    private static File uniqueFile(File dir, String name) { File f = new File(dir, name); if (!f.exists()) return f; int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : ""; for (int i = 2; i < 10000; i++) { f = new File(dir, base + " (" + i + ")" + ext); if (!f.exists()) return f; } return new File(dir, System.currentTimeMillis() + ext); }
    private static String makeFilename(String requested, String prefix) { String s = requested == null ? "" : requested.trim(); if (s.isEmpty()) s = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_"); if (!s.toLowerCase(Locale.US).endsWith(".mp4")) s += ".mp4"; return s; }
    private static String friendlyMessage(Exception e) { String m = e.getMessage(); if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName(); if (e instanceof UnknownHostException) return "无法连接服务器，请检查网络或地址。"; if (e instanceof SocketTimeoutException) return "网络超时，请重试。"; return m; }
    private static void deleteRecursive(File f) { if (f == null || !f.exists()) return; if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteRecursive(k); } f.delete(); }

    private void checkCancelled() throws CancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { cancelled = true; super.onDestroy(); }

    private static class Variant { String url, resolution; long bandwidth; Variant(String u,long b,String r){url=u;bandwidth=b;resolution=r==null?"":r;} }
    private static class MediaPlaylist { String sourceUrl, initUrl; long mediaSequence; boolean endList, hasByteRange; List<Segment> segments = new ArrayList<>(); }
    private static class Segment { String url; double duration; KeySpec key; Segment(String u,double d,KeySpec k){url=u;duration=d;key=k;} }
    private static class KeySpec { String url, iv; KeySpec(String u,String i){url=u;iv=i==null?"":i;} KeySpec copy(){return new KeySpec(url,iv);} }
    private static class LocalVariant { String path; long bandwidth; LocalVariant(String p,long b){path=p;bandwidth=b;} }
    private static class LocalPlaylist { String path, text; long mediaSequence; boolean endList, hasByteRange; File initFile; List<LocalSegment> segments = new ArrayList<>(); }
    private static class LocalSegment { String path; File file; double duration; LocalKeySpec key; LocalSegment(String p,File f,double d,LocalKeySpec k){path=p;file=f;duration=d;key=k;} }
    private static class LocalKeySpec { File file; String iv; LocalKeySpec(File f,String i){file=f;iv=i==null?"":i;} LocalKeySpec copy(){return new LocalKeySpec(file,iv);} }
    private static class FileRef { String path; File file; FileRef(String p,File f){path=p;file=f;} }
    private static class Resolution { int width,height; Resolution(int w,int h){width=w;height=h;} }
    private static class Box { int start,size,header; Box(int s,int z,int h){start=s;size=z;header=h;} int payloadStart(){return start+header;} int end(){return start+size;} }
    private static class CancelledException extends Exception {}
}
