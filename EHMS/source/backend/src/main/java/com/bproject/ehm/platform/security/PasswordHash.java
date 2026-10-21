package com.bproject.ehm.platform.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

/** Versioned, salted PBKDF2; no plaintext passwords or shared default password. */
public final class PasswordHash {
    private PasswordHash(){}
    private static final SecureRandom RANDOM=new SecureRandom();
    public static String hash(String password){
        validate(password);byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$600000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(derive(password,salt,600000));
    }
    public static boolean verify(String password,String stored){
        try{
            if(password==null||password.length()>128)return false;
            String[] parts=stored.split("\\$");if(parts.length!=4||!parts[0].equals("pbkdf2-sha256"))return false;
            int rounds=Integer.parseInt(parts[1]);if(rounds<600000||rounds>1200000)return false;
            byte[] salt=Base64.getDecoder().decode(parts[2]),expected=Base64.getDecoder().decode(parts[3]);
            return salt.length>=16&&expected.length==32&&MessageDigest.isEqual(expected,derive(password,salt,rounds));
        }catch(RuntimeException e){return false;}
    }
    public static void validate(String password){if(password==null||password.length()<12||password.length()>128||password.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Password must contain 12-128 non-control characters");}
    private static byte[] derive(String password,byte[] salt,int rounds){
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(GeneralSecurityException e){throw new IllegalStateException("Password hashing unavailable",e);}
        finally{spec.clearPassword();}
    }
    public static String token(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
}
