package com.oxclient.core.proxy;

import android.util.Base64;
import android.util.Log;

import com.oxclient.session.SessionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * LoginInjector
 *
 * Minecraft Bedrock Login paketi (0x01) C2S yönünde geçerken içindeki
 * JWT chain'i AccountManager'dan gelen mcToken ile değiştirir.
 *
 * Bedrock Login paketi yapısı (sıkıştırılmamış):
 *   VarInt   packetId     = 0x01
 *   Int32BE  protocol     (örn. 712 = 0x000002C8)  ← Big-endian dikkat!
 *   VarInt   chainLen     (zlib sıkıştırılmış chain byte uzunluğu)
 *   byte[]   chainData    (zlib deflate → {"chain":["jwt1","jwt2","jwt3"]})
 *   VarInt   skinLen      (zlib sıkıştırılmış skin/client data uzunluğu)
 *   byte[]   skinData     (zlib deflate → {...skindata...})
 *
 * Ne yapıyoruz:
 *   1. chainData'yı inflate et → JSON parse
 *   2. chain array'inin içindeki JWT'yi mcToken ile değiştir
 *   3. Yeni JSON'u deflate et → yeni Login paketi oluştur
 *   4. Skin data dokunulmadan geçirilir (boyutu değişmez)
 *
 * mcToken formatı:
 *   - Eğer JSON array string ise (["jwt1","jwt2"]) → doğrudan chain olarak kullan
 *   - Eğer tek JWT string ise → tek elemanlı chain yap
 */
public class LoginInjector {

    private static final String TAG = "LoginInjector";

    /**
     * Verilen Login paketi payload'ını (packetId VarInt dahil) intercept eder.
     * AccountManager'da kayıtlı mcToken yoksa orijinal payload döner.
     *
     * @param payload RakNet frame'den çıkan ham Bedrock payload
     * @return Değiştirilmiş payload ya da orijinal (token yoksa / hata varsa)
     */
    public static byte[] interceptLogin(byte[] payload) {
        // Kayıtlı hesap var mı?
        // Kotlin object property — Java'dan INSTANCE.getXxx() ile erişilir
        com.oxclient.auth.SavedAccount account =
            com.oxclient.auth.AccountManager.INSTANCE.getSelectedAccount();

        if (account == null) {
            Log.w(TAG, "Kayıtlı hesap yok — Login paketi değiştirilmeden geçiyor");
            return payload;
        }

        // Kotlin data class property → Java getter: getMcToken()
        String mcToken = account.getMcToken();
        if (mcToken == null || mcToken.isEmpty()) {
            Log.w(TAG, "mcToken boş — Login paketi değiştirilmeden geçiyor");
            return payload;
        }

        try {
            return rewriteLogin(payload, mcToken);
        } catch (Exception e) {
            Log.e(TAG, "Login inject başarısız: " + e.getMessage(), e);
            return payload; // Hata durumunda orijinal paketi boz, bağlantı kesilsin yerine orijinali geçir
        }
    }

    // ── Ana rewrite mantığı ───────────────────────────────────────────────

    private static byte[] rewriteLogin(byte[] payload, String mcToken) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);

        // 1. packetId VarInt oku (0x01)
        int packetIdStart = buf.position();
        int packetId = readVarInt(buf);
        int afterPacketId = buf.position();
        if (packetId != PacketIds.LOGIN) return payload;

        // 2. Protocol version — Big-endian Int32
        //    ByteBuffer Little-endian modunda olduğu için manuel okuyoruz
        if (buf.remaining() < 4) return payload;
        int b0 = buf.get() & 0xFF;
        int b1 = buf.get() & 0xFF;
        int b2 = buf.get() & 0xFF;
        int b3 = buf.get() & 0xFF;
        int protocol = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        Log.d(TAG, "Login protokol versiyonu: " + protocol);

        // 3. Chain data — VarInt uzunluk + zlib compressed bytes
        int chainCompressedLen = readVarInt(buf);
        if (chainCompressedLen <= 0 || chainCompressedLen > buf.remaining()) {
            Log.e(TAG, "Geçersiz chainLen=" + chainCompressedLen);
            return payload;
        }
        byte[] chainCompressed = new byte[chainCompressedLen];
        buf.get(chainCompressed);

        // 4. Skin data — VarInt uzunluk + sıkıştırılmış bytes (dokunmuyoruz)
        int skinStart = buf.position();
        byte[] skinRemainder = new byte[buf.remaining()];
        buf.get(skinRemainder);

        // 5. Chain'i inflate et
        byte[] chainJsonBytes = inflate(chainCompressed);
        String chainJsonStr = new String(chainJsonBytes, "UTF-8");
        Log.d(TAG, "Orijinal chain JSON (ilk 200): " + chainJsonStr.substring(0, Math.min(200, chainJsonStr.length())));

        // 6. Yeni chain JSON oluştur
        String newChainJson = buildNewChain(chainJsonStr, mcToken);
        Log.d(TAG, "Yeni chain JSON (ilk 200): " + newChainJson.substring(0, Math.min(200, newChainJson.length())));

        // 7. Yeni chain'i deflate et
        byte[] newChainCompressed = deflate(newChainJson.getBytes("UTF-8"));

        // 8. Yeni Login paketi oluştur
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 256);

        // packetId VarInt (0x01)
        writeVarInt(out, packetId);

        // Protocol version — Big-endian
        out.write((protocol >> 24) & 0xFF);
        out.write((protocol >> 16) & 0xFF);
        out.write((protocol >> 8)  & 0xFF);
        out.write((protocol)       & 0xFF);

        // Yeni chain
        writeVarInt(out, newChainCompressed.length);
        out.write(newChainCompressed);

        // Skin data — orijinali kopyala
        out.write(skinRemainder);

        byte[] result = out.toByteArray();
        Log.i(TAG, "✅ Login inject başarılı — orijinal: " + payload.length
                + " byte, yeni: " + result.length + " byte");
        return result;
    }

    // ── Chain JSON manipülasyonu ──────────────────────────────────────────

    /**
     * Orijinal chain JSON'undaki chain array'ini mcToken ile değiştirir.
     *
     * mcToken 2 formatta gelebilir:
     *   a) JSON array string: ["jwt1","jwt2","jwt3"]  → doğrudan kullan
     *   b) Tek JWT string: "eyJ..."                   → tek elemanlı array yap
     */
    private static String buildNewChain(String originalChainJson, String mcToken) throws Exception {
        JSONObject result = new JSONObject();

        // mcToken'ın hangi format olduğunu belirle
        JSONArray newChain;
        String trimmed = mcToken.trim();

        if (trimmed.startsWith("[")) {
            // Format (a): zaten JSON array
            newChain = new JSONArray(trimmed);
            Log.d(TAG, "mcToken format: JSON array, eleman sayısı=" + newChain.length());
        } else {
            // Format (b): tek JWT string
            newChain = new JSONArray();
            newChain.put(trimmed);
            Log.d(TAG, "mcToken format: tek JWT");
        }

        result.put("chain", newChain);
        return result.toString();
    }

    // ── Zlib helpers ─────────────────────────────────────────────────────

    private static byte[] inflate(byte[] data) throws Exception {
        ByteArrayInputStream bin = new ByteArrayInputStream(data);
        InflaterInputStream inf  = new InflaterInputStream(bin);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = inf.read(buf)) != -1) out.write(buf, 0, n);
        inf.close();
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeflaterOutputStream def  = new DeflaterOutputStream(out);
        def.write(data);
        def.finish();
        def.close();
        return out.toByteArray();
    }

    // ── VarInt helpers ────────────────────────────────────────────────────

    private static int readVarInt(ByteBuffer b) {
        int v = 0, s = 0;
        byte c;
        do {
            if (!b.hasRemaining()) break;
            c = b.get();
            v |= (c & 0x7F) << s;
            s += 7;
        } while ((c & 0x80) != 0 && s < 35);
        return v;
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        do {
            byte c = (byte)(v & 0x7F);
            v >>>= 7;
            if (v != 0) c |= (byte) 0x80;
            out.write(c);
        } while (v != 0);
    }
}

