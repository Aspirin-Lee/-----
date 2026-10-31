package org.labday.app;
import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

public class Credentials {
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias("labday-access"))return (SecretKey)ks.getKey("labday-access",null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder("labday-access",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    public static void save(Context c,String url,String token) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        JSONObject data=new JSONObject();data.put("url",url);data.put("token",token);
        byte[] bytes=cipher.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!c.getSharedPreferences("connection",0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())throw new java.io.IOException("连接保存失败");
    }
    public static JSONObject read(Context c) throws Exception {
        var prefs=c.getSharedPreferences("connection",0);String data=prefs.getString("data","");
        if(data.isEmpty())return null;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
    }
    public static void clear(Context c){c.getSharedPreferences("connection",0).edit().clear().commit();}
}
