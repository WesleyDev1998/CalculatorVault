package com.secure.calcvault;

public class MediaItem {

    private final String id;
    private final String encryptedFileName;
    private final String originalName;
    private final String mimeType;
    private final long timestamp;

    public MediaItem(String id, String encryptedFileName, String originalName, String mimeType, long timestamp) {
        this.id = id;
        this.encryptedFileName = encryptedFileName;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getEncryptedFileName() {
        return encryptedFileName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith("video/");
    }
}
