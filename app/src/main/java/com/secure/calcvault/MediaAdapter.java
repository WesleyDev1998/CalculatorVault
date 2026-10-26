package com.secure.calcvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    public interface OnMediaClickListener {
        void onMediaClick(int position, MediaItem item);
        void onMediaLongClick(MediaItem item);
    }

    private final Context context;
    private final List<MediaItem> mediaList;
    private final File vaultDirectory;
    private final SecretKey secretKey;
    private final OnMediaClickListener listener;

    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    public MediaAdapter(Context context, List<MediaItem> mediaList, File vaultDirectory,
                        SecretKey secretKey, OnMediaClickListener listener) {
        this.context = context;
        this.mediaList = mediaList;
        this.vaultDirectory = vaultDirectory;
        this.secretKey = secretKey;
        this.listener = listener;

        this.executorService = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());

        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        this.memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_vault_media, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaItem item = mediaList.get(position);
        holder.tvMediaName.setText(item.getOriginalName());
        holder.videoBadge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

        holder.ivThumbnail.setTag(item.getId());
        holder.ivThumbnail.setImageBitmap(null);

        Bitmap cachedBitmap = memoryCache.get(item.getId());
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            holder.ivThumbnail.setImageBitmap(cachedBitmap);
        } else {
            executorService.execute(() -> {
                File encryptedFile = new File(vaultDirectory, item.getEncryptedFileName());
                Bitmap thumb = CryptoManager.decryptThumbnailInMemory(
                        context, encryptedFile, item.isVideo(), 280, secretKey
                );

                if (thumb != null) {
                    memoryCache.put(item.getId(), thumb);
                    mainHandler.post(() -> {
                        if (item.getId().equals(holder.ivThumbnail.getTag())) {
                            holder.ivThumbnail.setImageBitmap(thumb);
                        }
                    });
                }
            });
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int currentPos = holder.getBindingAdapterPosition();
                if (currentPos != RecyclerView.NO_POSITION) {
                    listener.onMediaClick(currentPos, mediaList.get(currentPos));
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onMediaLongClick(item);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mediaList.size();
    }

    public void releaseMemory() {
        executorService.shutdownNow();
        memoryCache.evictAll();
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivThumbnail;
        final LinearLayout videoBadge;
        final TextView tvMediaName;

        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            videoBadge = itemView.findViewById(R.id.videoBadge);
            tvMediaName = itemView.findViewById(R.id.tvMediaName);
        }
    }
}
