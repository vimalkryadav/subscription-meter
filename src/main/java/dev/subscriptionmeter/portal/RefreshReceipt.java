package dev.subscriptionmeter.portal;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** A short retry receipt is encrypted with the presented bearer, never a database-stored key. */
final class RefreshReceipt {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static Cipher cipher(int mode,String token,byte[] nonce)throws GeneralSecurityException{
        byte[] key=MessageDigest.getInstance("SHA-256").digest(("control-usage-refresh-receipt-v1:"+token).getBytes(StandardCharsets.UTF_8));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        return cipher;
    }
    static String seal(String token,String access,String refresh){try{
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);
        byte[] payload=cipher(Cipher.ENCRYPT_MODE,token,nonce).doFinal((access+"."+refresh).getBytes(StandardCharsets.UTF_8));
        byte[] result=Arrays.copyOf(nonce,nonce.length+payload.length);System.arraycopy(payload,0,result,nonce.length,payload.length);
        return Base64.getEncoder().encodeToString(result);
    }catch(GeneralSecurityException e){throw new IllegalStateException("Cannot protect refresh receipt",e);}}
    static String[] open(String token,String receipt){try{
        byte[] data=Base64.getDecoder().decode(receipt);
        return new String(cipher(Cipher.DECRYPT_MODE,token,Arrays.copyOf(data,12))
                .doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8).split("\\.",2);
    }catch(GeneralSecurityException|IllegalArgumentException e){throw new IllegalStateException("Cannot read refresh receipt",e);}}
}
