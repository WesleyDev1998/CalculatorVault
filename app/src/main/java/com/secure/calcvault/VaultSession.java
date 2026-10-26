package com.secure.calcvault;

import java.io.File;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gerenciador de sessão volátil em memória RAM para o Cofre ativo (Real ou Falso).
 * Mantém a Chave Mestra AES-256 desencapsulada apenas enquanto o cofre está em primeiro plano.
 */
public class VaultSession {

    private static VaultSession instance;

    private byte[] rawMasterAesKey;
    private File vaultDirectory;
    private boolean isDecoySession;

    private VaultSession() {
    }

    public static synchronized VaultSession getInstance() {
        if (instance == null) {
            instance = new VaultSession();
        }
        return instance;
    }

    public synchronized void startSession(byte[] masterKeyBytes, File directory, boolean isDecoySession) {
        clearSession();
        if (masterKeyBytes != null) {
            this.rawMasterAesKey = Arrays.copyOf(masterKeyBytes, masterKeyBytes.length);
            Arrays.fill(masterKeyBytes, (byte) 0);
        }
        this.vaultDirectory = directory;
        this.isDecoySession = isDecoySession;
    }

    public synchronized SecretKey getSecretKey() {
        if (rawMasterAesKey == null || rawMasterAesKey.length != 32) {
            return null;
        }
        return new SecretKeySpec(rawMasterAesKey, "AES");
    }

    public synchronized byte[] getRawMasterKeyCopy() {
        if (rawMasterAesKey == null) {
            return null;
        }
        return Arrays.copyOf(rawMasterAesKey, rawMasterAesKey.length);
    }

    public synchronized File getVaultDirectory() {
        return vaultDirectory;
    }

    public synchronized boolean isActive() {
        return rawMasterAesKey != null && rawMasterAesKey.length == 32 && vaultDirectory != null;
    }

    public synchronized boolean isDecoySession() {
        return isDecoySession;
    }

    public synchronized void clearSession() {
        if (rawMasterAesKey != null) {
            Arrays.fill(rawMasterAesKey, (byte) 0);
            rawMasterAesKey = null;
        }
        vaultDirectory = null;
        isDecoySession = false;
    }
}
