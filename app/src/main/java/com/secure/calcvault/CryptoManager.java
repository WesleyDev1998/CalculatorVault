package com.secure.calcvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Motor Criptográfico Militar Zero-Knowledge com Key Wrapping (AES-256-GCM + PBKDF2WithHmacSHA256).
 * Permite redefinir a senha do cofre ativo (Real ou Falso) instantaneamente sem recriptografar as mídias.
 */
public class CryptoManager {

    private static final String PREFS_NAME = "calc_sys_prefs_v2";
    private static final String KEY_IS_CONFIGURED = "sys_cfg_done";

    private static final String KEY_REAL_AUTH_SALT = "r_a_salt";
    private static final String KEY_REAL_WRAP_SALT = "r_w_salt";
    private static final String KEY_REAL_VERIFIER = "r_verifier";
    private static final String KEY_REAL_WRAPPED_MASTER = "r_w_master";

    private static final String KEY_DECOY_AUTH_SALT = "d_a_salt";
    private static final String KEY_DECOY_WRAP_SALT = "d_w_salt";
    private static final String KEY_DECOY_VERIFIER = "d_verifier";
    private static final String KEY_DECOY_WRAPPED_MASTER = "d_w_master";

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int STREAM_BUFFER_SIZE = 65536;

    public static final String REAL_VAULT_DIR_NAME = ".sys_cache_r";
    public static final String DECOY_VAULT_DIR_NAME = ".sys_cache_d";
    private static final String INDEX_FILE_NAME = "index.enc";

    public static final int AUTH_FAILED = 0;
    public static final int AUTH_REAL_VAULT = 1;
    public static final int AUTH_DECOY_VAULT = 2;

    public static final int PWD_CHANGE_SUCCESS = 0;
    public static final int PWD_CHANGE_CONFLICT_OTHER_VAULT = 1;
    public static final int PWD_CHANGE_INVALID_FORMAT = 2;
    public static final int PWD_CHANGE_ERROR = 3;

    public static boolean isVaultConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_IS_CONFIGURED, false);
    }

    public static boolean setupPasswords(Context context, String realPassword, String decoyPassword) {
        try {
            SecureRandom sr = new SecureRandom();

            byte[] realMasterKey = new byte[32];
            byte[] decoyMasterKey = new byte[32];
            sr.nextBytes(realMasterKey);
            sr.nextBytes(decoyMasterKey);

            byte[] realAuthSalt = new byte[SALT_LENGTH_BYTES];
            byte[] realWrapSalt = new byte[SALT_LENGTH_BYTES];
            sr.nextBytes(realAuthSalt);
            sr.nextBytes(realWrapSalt);

            byte[] decoyAuthSalt = new byte[SALT_LENGTH_BYTES];
            byte[] decoyWrapSalt = new byte[SALT_LENGTH_BYTES];
            sr.nextBytes(decoyAuthSalt);
            sr.nextBytes(decoyWrapSalt);

            byte[] realVerifier = derivePbkdf2(realPassword.toCharArray(), realAuthSalt);
            byte[] realKek = derivePbkdf2(realPassword.toCharArray(), realWrapSalt);
            byte[] wrappedRealMaster = wrapMasterKey(realMasterKey, realKek);

            byte[] decoyVerifier = derivePbkdf2(decoyPassword.toCharArray(), decoyAuthSalt);
            byte[] decoyKek = derivePbkdf2(decoyPassword.toCharArray(), decoyWrapSalt);
            byte[] wrappedDecoyMaster = wrapMasterKey(decoyMasterKey, decoyKek);

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_REAL_AUTH_SALT, Base64.encodeToString(realAuthSalt, Base64.NO_WRAP))
                    .putString(KEY_REAL_WRAP_SALT, Base64.encodeToString(realWrapSalt, Base64.NO_WRAP))
                    .putString(KEY_REAL_VERIFIER, Base64.encodeToString(realVerifier, Base64.NO_WRAP))
                    .putString(KEY_REAL_WRAPPED_MASTER, Base64.encodeToString(wrappedRealMaster, Base64.NO_WRAP))
                    .putString(KEY_DECOY_AUTH_SALT, Base64.encodeToString(decoyAuthSalt, Base64.NO_WRAP))
                    .putString(KEY_DECOY_WRAP_SALT, Base64.encodeToString(decoyWrapSalt, Base64.NO_WRAP))
                    .putString(KEY_DECOY_VERIFIER, Base64.encodeToString(decoyVerifier, Base64.NO_WRAP))
                    .putString(KEY_DECOY_WRAPPED_MASTER, Base64.encodeToString(wrappedDecoyMaster, Base64.NO_WRAP))
                    .putBoolean(KEY_IS_CONFIGURED, true)
                    .commit();

            Arrays.fill(realMasterKey, (byte) 0);
            Arrays.fill(decoyMasterKey, (byte) 0);
            Arrays.fill(realVerifier, (byte) 0);
            Arrays.fill(realKek, (byte) 0);
            Arrays.fill(decoyVerifier, (byte) 0);
            Arrays.fill(decoyKek, (byte) 0);

            prepareVaultDirectory(new File(context.getFilesDir(), REAL_VAULT_DIR_NAME));
            prepareVaultDirectory(new File(context.getFilesDir(), DECOY_VAULT_DIR_NAME));

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static int changeActiveVaultPassword(Context context, String newPassword) {
        if (newPassword == null || newPassword.length() < 4 || "0000".equals(newPassword)) {
            return PWD_CHANGE_INVALID_FORMAT;
        }
        for (int i = 0; i < newPassword.length(); i++) {
            char c = newPassword.charAt(i);
            if (c < '0' || c > '9') {
                return PWD_CHANGE_INVALID_FORMAT;
            }
        }

        if (!VaultSession.getInstance().isActive()) {
            return PWD_CHANGE_ERROR;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isDecoy = VaultSession.getInstance().isDecoySession();
        char[] newPwdChars = newPassword.toCharArray();
        byte[] masterKeyCopy = null;

        try {
            String otherAuthSaltB64 = prefs.getString(isDecoy ? KEY_REAL_AUTH_SALT : KEY_DECOY_AUTH_SALT, "");
            String otherVerifierB64 = prefs.getString(isDecoy ? KEY_REAL_VERIFIER : KEY_DECOY_VERIFIER, "");
            byte[] otherAuthSalt = Base64.decode(otherAuthSaltB64, Base64.NO_WRAP);
            byte[] otherStoredVerifier = Base64.decode(otherVerifierB64, Base64.NO_WRAP);
            byte[] candidateAgainstOther = derivePbkdf2(newPwdChars, otherAuthSalt);

            boolean conflict = MessageDigest.isEqual(otherStoredVerifier, candidateAgainstOther);
            Arrays.fill(candidateAgainstOther, (byte) 0);
            if (conflict) {
                return PWD_CHANGE_CONFLICT_OTHER_VAULT;
            }

            masterKeyCopy = VaultSession.getInstance().getRawMasterKeyCopy();
            if (masterKeyCopy == null || masterKeyCopy.length != 32) {
                return PWD_CHANGE_ERROR;
            }

            SecureRandom sr = new SecureRandom();
            byte[] newAuthSalt = new byte[SALT_LENGTH_BYTES];
            byte[] newWrapSalt = new byte[SALT_LENGTH_BYTES];
            sr.nextBytes(newAuthSalt);
            sr.nextBytes(newWrapSalt);

            byte[] newVerifier = derivePbkdf2(newPwdChars, newAuthSalt);
            byte[] newKek = derivePbkdf2(newPwdChars, newWrapSalt);
            byte[] newWrappedMaster = wrapMasterKey(masterKeyCopy, newKek);

            SharedPreferences.Editor editor = prefs.edit();
            if (!isDecoy) {
                editor.putString(KEY_REAL_AUTH_SALT, Base64.encodeToString(newAuthSalt, Base64.NO_WRAP));
                editor.putString(KEY_REAL_WRAP_SALT, Base64.encodeToString(newWrapSalt, Base64.NO_WRAP));
                editor.putString(KEY_REAL_VERIFIER, Base64.encodeToString(newVerifier, Base64.NO_WRAP));
                editor.putString(KEY_REAL_WRAPPED_MASTER, Base64.encodeToString(newWrappedMaster, Base64.NO_WRAP));
            } else {
                editor.putString(KEY_DECOY_AUTH_SALT, Base64.encodeToString(newAuthSalt, Base64.NO_WRAP));
                editor.putString(KEY_DECOY_WRAP_SALT, Base64.encodeToString(newWrapSalt, Base64.NO_WRAP));
                editor.putString(KEY_DECOY_VERIFIER, Base64.encodeToString(newVerifier, Base64.NO_WRAP));
                editor.putString(KEY_DECOY_WRAPPED_MASTER, Base64.encodeToString(newWrappedMaster, Base64.NO_WRAP));
            }
            editor.commit();

            Arrays.fill(newVerifier, (byte) 0);
            Arrays.fill(newKek, (byte) 0);
            return PWD_CHANGE_SUCCESS;

        } catch (Exception e) {
            return PWD_CHANGE_ERROR;
        } finally {
            Arrays.fill(newPwdChars, '\0');
            if (masterKeyCopy != null) {
                Arrays.fill(masterKeyCopy, (byte) 0);
            }
        }
    }

    public static void prepareVaultDirectory(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File noMedia = new File(dir, ".nomedia");
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile();
            } catch (IOException ignored) {
            }
        }
    }

    public static int authenticateAndInitSession(Context context, String inputPassword) {
        if (inputPassword == null || inputPassword.isEmpty() || !isVaultConfigured(context)) {
            return AUTH_FAILED;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        char[] passwordChars = inputPassword.toCharArray();

        try {
            byte[] realAuthSalt = Base64.decode(prefs.getString(KEY_REAL_AUTH_SALT, ""), Base64.NO_WRAP);
            byte[] storedRealVerifier = Base64.decode(prefs.getString(KEY_REAL_VERIFIER, ""), Base64.NO_WRAP);
            byte[] candidateRealVerifier = derivePbkdf2(passwordChars, realAuthSalt);

            if (MessageDigest.isEqual(storedRealVerifier, candidateRealVerifier)) {
                Arrays.fill(candidateRealVerifier, (byte) 0);
                byte[] realWrapSalt = Base64.decode(prefs.getString(KEY_REAL_WRAP_SALT, ""), Base64.NO_WRAP);
                byte[] wrappedRealMaster = Base64.decode(prefs.getString(KEY_REAL_WRAPPED_MASTER, ""), Base64.NO_WRAP);
                byte[] realKek = derivePbkdf2(passwordChars, realWrapSalt);
                byte[] masterKey = unwrapMasterKey(wrappedRealMaster, realKek);
                Arrays.fill(realKek, (byte) 0);

                File realDir = new File(context.getFilesDir(), REAL_VAULT_DIR_NAME);
                prepareVaultDirectory(realDir);
                VaultSession.getInstance().startSession(masterKey, realDir, false);
                return AUTH_REAL_VAULT;
            }
            Arrays.fill(candidateRealVerifier, (byte) 0);

            byte[] decoyAuthSalt = Base64.decode(prefs.getString(KEY_DECOY_AUTH_SALT, ""), Base64.NO_WRAP);
            byte[] storedDecoyVerifier = Base64.decode(prefs.getString(KEY_DECOY_VERIFIER, ""), Base64.NO_WRAP);
            byte[] candidateDecoyVerifier = derivePbkdf2(passwordChars, decoyAuthSalt);

            if (MessageDigest.isEqual(storedDecoyVerifier, candidateDecoyVerifier)) {
                Arrays.fill(candidateDecoyVerifier, (byte) 0);
                byte[] decoyWrapSalt = Base64.decode(prefs.getString(KEY_DECOY_WRAP_SALT, ""), Base64.NO_WRAP);
                byte[] wrappedDecoyMaster = Base64.decode(prefs.getString(KEY_DECOY_WRAPPED_MASTER, ""), Base64.NO_WRAP);
                byte[] decoyKek = derivePbkdf2(passwordChars, decoyWrapSalt);
                byte[] masterKey = unwrapMasterKey(wrappedDecoyMaster, decoyKek);
                Arrays.fill(decoyKek, (byte) 0);

                File decoyDir = new File(context.getFilesDir(), DECOY_VAULT_DIR_NAME);
                prepareVaultDirectory(decoyDir);
                VaultSession.getInstance().startSession(masterKey, decoyDir, true);
                return AUTH_DECOY_VAULT;
            }
            Arrays.fill(candidateDecoyVerifier, (byte) 0);

        } catch (Exception ignored) {
        } finally {
            Arrays.fill(passwordChars, '\0');
        }

        return AUTH_FAILED;
    }

    private static byte[] derivePbkdf2(char[] password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        return factory.generateSecret(spec).getEncoded();
    }

    private static byte[] wrapMasterKey(byte[] masterKey, byte[] kekBytes) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        SecretKey kek = new SecretKeySpec(kekBytes, "AES");
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] cipherText = cipher.doFinal(masterKey);

        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
        return combined;
    }

    private static byte[] unwrapMasterKey(byte[] wrappedData, byte[] kekBytes) throws Exception {
        byte[] iv = Arrays.copyOfRange(wrappedData, 0, GCM_IV_LENGTH_BYTES);
        byte[] cipherText = Arrays.copyOfRange(wrappedData, GCM_IV_LENGTH_BYTES, wrappedData.length);

        SecretKey kek = new SecretKeySpec(kekBytes, "AES");
        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(cipherText);
    }

    public static void encryptStreamToFile(InputStream inputStream, File destFile, SecretKey secretKey) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            fos.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    cos.write(buffer, 0, bytesRead);
                }
                cos.flush();
            }
        }
    }

    public static void decryptFileToStream(File encryptedFile, OutputStream outputStream, SecretKey secretKey) throws Exception {
        try (FileInputStream fis = new FileInputStream(encryptedFile)) {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            int ivRead = 0;
            while (ivRead < GCM_IV_LENGTH_BYTES) {
                int r = fis.read(iv, ivRead, GCM_IV_LENGTH_BYTES - ivRead);
                if (r == -1) {
                    throw new IOException("Arquivo criptografado truncado.");
                }
                ivRead += r;
            }

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            try (CipherInputStream cis = new CipherInputStream(fis, cipher)) {
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = cis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
        }
    }

    public static byte[] decryptFileToBytes(File encryptedFile, SecretKey secretKey) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        decryptFileToStream(encryptedFile, baos, secretKey);
        return baos.toByteArray();
    }

    public static Bitmap decryptThumbnailInMemory(Context context, File encryptedFile, boolean isVideo, int reqSize, SecretKey secretKey) {
        if (encryptedFile == null || !encryptedFile.exists() || secretKey == null) {
            return null;
        }

        if (!isVideo) {
            byte[] imageBytes = null;
            try {
                imageBytes = decryptFileToBytes(encryptedFile, secretKey);

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

                options.inSampleSize = calculateInSampleSize(options, reqSize, reqSize);
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;

                return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
            } catch (Exception e) {
                return null;
            } finally {
                if (imageBytes != null) {
                    Arrays.fill(imageBytes, (byte) 0);
                }
            }
        } else {
            File tempVideo = new File(context.getCacheDir(), ".thumb_" + System.nanoTime() + ".tmp");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try (FileOutputStream fos = new FileOutputStream(tempVideo)) {
                decryptFileToStream(encryptedFile, fos, secretKey);
                retriever.setDataSource(tempVideo.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) {
                    frame = retriever.getFrameAtTime();
                }
                if (frame != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, reqSize, reqSize, true);
                    if (scaled != frame) {
                        frame.recycle();
                    }
                    return scaled;
                }
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
                if (tempVideo.exists()) {
                    tempVideo.delete();
                }
            }
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static synchronized List<MediaItem> loadMediaIndex(File vaultDir, SecretKey secretKey) {
        List<MediaItem> items = new ArrayList<>();
        if (vaultDir == null || secretKey == null) {
            return items;
        }

        File indexFile = new File(vaultDir, INDEX_FILE_NAME);
        if (!indexFile.exists()) {
            return items;
        }

        byte[] decryptedBytes = null;
        try {
            decryptedBytes = decryptFileToBytes(indexFile, secretKey);
            String jsonStr = new String(decryptedBytes, StandardCharsets.UTF_8);
            JSONArray jsonArray = new JSONArray(jsonStr);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                MediaItem item = new MediaItem(
                        obj.getString("id"),
                        obj.getString("encryptedFileName"),
                        obj.getString("originalName"),
                        obj.getString("mimeType"),
                        obj.getLong("timestamp")
                );
                File mediaBin = new File(vaultDir, item.getEncryptedFileName());
                if (mediaBin.exists()) {
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
        }
        return items;
    }

    public static synchronized boolean saveMediaIndex(File vaultDir, SecretKey secretKey, List<MediaItem> items) {
        if (vaultDir == null || secretKey == null) {
            return false;
        }

        byte[] rawJsonBytes = null;
        try {
            JSONArray jsonArray = new JSONArray();
            for (MediaItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.getId());
                obj.put("encryptedFileName", item.getEncryptedFileName());
                obj.put("originalName", item.getOriginalName());
                obj.put("mimeType", item.getMimeType());
                obj.put("timestamp", item.getTimestamp());
                jsonArray.put(obj);
            }

            rawJsonBytes = jsonArray.toString().getBytes(StandardCharsets.UTF_8);
            File tempIndexFile = new File(vaultDir, INDEX_FILE_NAME + ".tmp");
            File finalIndexFile = new File(vaultDir, INDEX_FILE_NAME);

            try (ByteArrayInputStream bais = new ByteArrayInputStream(rawJsonBytes)) {
                encryptStreamToFile(bais, tempIndexFile, secretKey);
            }

            if (finalIndexFile.exists()) {
                finalIndexFile.delete();
            }
            return tempIndexFile.renameTo(finalIndexFile);
        } catch (Exception e) {
            return false;
        } finally {
            if (rawJsonBytes != null) {
                Arrays.fill(rawJsonBytes, (byte) 0);
            }
        }
    }
}
