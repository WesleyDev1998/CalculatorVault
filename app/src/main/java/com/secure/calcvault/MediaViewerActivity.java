package com.secure.calcvault;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * Visualizador deslizante (ViewPager2) com suporte a puxar para o lado (Swipe)
 * e botões "Anterior" / "Próxima" na tela.
 */
public class MediaViewerActivity extends AppCompatActivity {

    private ViewPager2 viewPagerMedia;
    private TextView tvViewerFileName;
    private TextView tvViewerCounter;
    private MaterialButton btnPrevMedia;
    private MaterialButton btnNextMedia;

    private final List<MediaItem> mediaList = new ArrayList<>();
    private final List<File> tempVideoFiles = new ArrayList<>();
    private ExecutorService decryptExecutor;
    private boolean isReturningToVaultGrid = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
        );

        if (!VaultSession.getInstance().isActive()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_media_viewer);

        viewPagerMedia = findViewById(R.id.viewPagerMedia);
        tvViewerFileName = findViewById(R.id.tvViewerFileName);
        tvViewerCounter = findViewById(R.id.tvViewerCounter);
        btnPrevMedia = findViewById(R.id.btnPrevMedia);
        btnNextMedia = findViewById(R.id.btnNextMedia);
        MaterialButton btnCloseViewer = findViewById(R.id.btnCloseViewer);

        decryptExecutor = Executors.newFixedThreadPool(2);

        File vaultDir = VaultSession.getInstance().getVaultDirectory();
        SecretKey secretKey = VaultSession.getInstance().getSecretKey();

        List<MediaItem> loaded = CryptoManager.loadMediaIndex(vaultDir, secretKey);
        Collections.sort(loaded, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        mediaList.addAll(loaded);

        if (mediaList.isEmpty()) {
            finish();
            return;
        }

        int initialPos = getIntent().getIntExtra("INITIAL_POSITION", 0);
        if (initialPos < 0 || initialPos >= mediaList.size()) {
            initialPos = 0;
        }

        ViewerPagerAdapter adapter = new ViewerPagerAdapter(vaultDir, secretKey);
        viewPagerMedia.setAdapter(adapter);
        viewPagerMedia.setCurrentItem(initialPos, false);
        updateHeaderAndButtons(initialPos);

        viewPagerMedia.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateHeaderAndButtons(position);
            }
        });

        btnPrevMedia.setOnClickListener(v -> {
            int curr = viewPagerMedia.getCurrentItem();
            if (curr > 0) {
                viewPagerMedia.setCurrentItem(curr - 1, true);
            }
        });

        btnNextMedia.setOnClickListener(v -> {
            int curr = viewPagerMedia.getCurrentItem();
            if (curr < mediaList.size() - 1) {
                viewPagerMedia.setCurrentItem(curr + 1, true);
            }
        });

        btnCloseViewer.setOnClickListener(v -> returnToVaultGrid());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                returnToVaultGrid();
            }
        });
    }

    private void updateHeaderAndButtons(int position) {
        if (position >= 0 && position < mediaList.size()) {
            MediaItem current = mediaList.get(position);
            tvViewerFileName.setText(current.getOriginalName());
            tvViewerCounter.setText((position + 1) + " / " + mediaList.size());
            btnPrevMedia.setEnabled(position > 0);
            btnPrevMedia.setAlpha(position > 0 ? 1.0f : 0.4f);
            btnNextMedia.setEnabled(position < mediaList.size() - 1);
            btnNextMedia.setAlpha(position < mediaList.size() - 1 ? 1.0f : 0.4f);
        }
    }

    private void returnToVaultGrid() {
        isReturningToVaultGrid = true;
        wipeTemporaryResources();
        finish();
    }

    private synchronized void wipeTemporaryResources() {
        for (File temp : tempVideoFiles) {
            if (temp != null && temp.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(temp, "rws")) {
                    long scrubLen = Math.min(temp.length(), 131072);
                    byte[] zeros = new byte[(int) scrubLen];
                    raf.seek(0);
                    raf.write(zeros);
                } catch (Exception ignored) {
                }
                temp.delete();
            }
        }
        tempVideoFiles.clear();
    }

    @Override
    protected void onStop() {
        super.onStop();
        wipeTemporaryResources();
        if (!isReturningToVaultGrid && !isChangingConfigurations()) {
            VaultSession.getInstance().clearSession();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        wipeTemporaryResources();
        if (decryptExecutor != null) {
            decryptExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private class ViewerPagerAdapter extends RecyclerView.Adapter<ViewerPagerAdapter.PageViewHolder> {

        private final File vaultDir;
        private final SecretKey secretKey;

        ViewerPagerAdapter(File vaultDir, SecretKey secretKey) {
            this.vaultDir = vaultDir;
            this.secretKey = secretKey;
        }

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            ImageView imageView = new ImageView(parent.getContext());
            imageView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

            VideoView videoView = new VideoView(parent.getContext());
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            videoParams.gravity = Gravity.CENTER;
            videoView.setLayoutParams(videoParams);
            videoView.setVisibility(View.GONE);

            ProgressBar progressBar = new ProgressBar(parent.getContext());
            FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(120, 120);
            pbParams.gravity = Gravity.CENTER;
            progressBar.setLayoutParams(pbParams);

            root.addView(imageView);
            root.addView(videoView);
            root.addView(progressBar);

            return new PageViewHolder(root, imageView, videoView, progressBar);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            MediaItem item = mediaList.get(position);
            holder.imageView.setImageBitmap(null);
            holder.imageView.setVisibility(View.GONE);
            holder.videoView.setVisibility(View.GONE);
            holder.progressBar.setVisibility(View.VISIBLE);

            File encryptedFile = new File(vaultDir, item.getEncryptedFileName());

            if (!item.isVideo()) {
                decryptExecutor.execute(() -> {
                    byte[] decryptedBytes = null;
                    try {
                        decryptedBytes = CryptoManager.decryptFileToBytes(encryptedFile, secretKey);
                        DisplayMetrics metrics = getResources().getDisplayMetrics();
                        int maxW = Math.max(metrics.widthPixels, 1920);
                        int maxH = Math.max(metrics.heightPixels, 1920);

                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length, bounds);

                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = CryptoManager.calculateInSampleSize(bounds, maxW, maxH);
                        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                        final Bitmap bmp = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.length, opts);
                        runOnUiThread(() -> {
                            holder.progressBar.setVisibility(View.GONE);
                            holder.imageView.setVisibility(View.VISIBLE);
                            holder.imageView.setImageBitmap(bmp);
                        });
                    } catch (Exception ignored) {
                    } finally {
                        if (decryptedBytes != null) {
                            Arrays.fill(decryptedBytes, (byte) 0);
                        }
                    }
                });
            } else {
                decryptExecutor.execute(() -> {
                    try {
                        File tempVideo = new File(getCacheDir(), ".play_buf_" + System.nanoTime() + ".mp4");
                        synchronized (MediaViewerActivity.this) {
                            tempVideoFiles.add(tempVideo);
                        }
                        try (FileOutputStream fos = new FileOutputStream(tempVideo)) {
                            CryptoManager.decryptFileToStream(encryptedFile, fos, secretKey);
                        }
                        runOnUiThread(() -> {
                            holder.progressBar.setVisibility(View.GONE);
                            holder.videoView.setVisibility(View.VISIBLE);
                            MediaController mc = new MediaController(MediaViewerActivity.this);
                            mc.setAnchorView(holder.videoView);
                            holder.videoView.setMediaController(mc);
                            holder.videoView.setVideoURI(Uri.fromFile(tempVideo));
                            holder.videoView.setOnPreparedListener(mp -> holder.videoView.start());
                        });
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return mediaList.size();
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            final ImageView imageView;
            final VideoView videoView;
            final ProgressBar progressBar;

            PageViewHolder(@NonNull View itemView, ImageView imageView, VideoView videoView, ProgressBar progressBar) {
                super(itemView);
                this.imageView = imageView;
                this.videoView = videoView;
                this.progressBar = progressBar;
            }
        }
    }
}
