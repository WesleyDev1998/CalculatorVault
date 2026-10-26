package com.secure.calcvault;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

public class VaultActivity extends AppCompatActivity implements MediaAdapter.OnMediaClickListener {

    private DrawerLayout drawerLayout;
    private RecyclerView rvMediaGrid;
    private LinearLayout emptyStateContainer;
    private FrameLayout loadingOverlay;
    private TextView tvLoadingMessage;

    private final List<MediaItem> mediaItems = new ArrayList<>();
    private MediaAdapter mediaAdapter;
    private ExecutorService ioExecutor;

    private boolean isLaunchingSystemDialog = false;
    private boolean isNavigatingToViewer = false;

    private ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher;
    private ActivityResultLauncher<IntentSenderRequest> deleteOriginalsLauncher;

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

        setContentView(R.layout.activity_vault);

        drawerLayout = findViewById(R.id.drawerLayout);
        rvMediaGrid = findViewById(R.id.rvMediaGrid);
        emptyStateContainer = findViewById(R.id.emptyStateContainer);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage);

        // Detalhe sutil exclusivo para o dono: ponto final discreto ("Cofre Privado.") no cofre principal
        // e sem ponto ("Cofre Privado") no cofre secundário, sem jamais exibir a palavra "Falso".
        TextView tvVaultTitle = findViewById(R.id.tvVaultTitle);
        if (tvVaultTitle != null) {
            tvVaultTitle.setText(VaultSession.getInstance().isDecoySession() ? "Cofre Privado" : "Cofre Privado.");
        }

        MaterialButton btnOpenDrawer = findViewById(R.id.btnOpenDrawer);
        MaterialButton btnLockVault = findViewById(R.id.btnLockVault);
        FloatingActionButton fabAddMedia = findViewById(R.id.fabAddMedia);

        MaterialButton btnChangeVaultPassword = findViewById(R.id.btnChangeVaultPassword);
        MaterialButton btnSecurityInfo = findViewById(R.id.btnSecurityInfo);
        MaterialButton btnDrawerLockNow = findViewById(R.id.btnDrawerLockNow);

        ioExecutor = Executors.newSingleThreadExecutor();

        rvMediaGrid.setLayoutManager(new GridLayoutManager(this, 3));
        mediaAdapter = new MediaAdapter(
                this,
                mediaItems,
                VaultSession.getInstance().getVaultDirectory(),
                VaultSession.getInstance().getSecretKey(),
                this
        );
        rvMediaGrid.setAdapter(mediaAdapter);

        registerActivityLaunchers();

        btnOpenDrawer.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        btnLockVault.setOnClickListener(v -> lockAndExitVault());
        btnDrawerLockNow.setOnClickListener(v -> lockAndExitVault());
        fabAddMedia.setOnClickListener(v -> launchMediaPicker());

        btnChangeVaultPassword.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            showChangePasswordDialog();
        });

        btnSecurityInfo.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            new AlertDialog.Builder(this)
                    .setTitle("Segurança Criptográfica")
                    .setMessage("• Algoritmo: AES-256-GCM Autenticado\n• Derivação: PBKDF2-HMAC-SHA256 (65.536 iterações)\n• Key Wrapping: Troca de senha instantânea sem recriptografar vídeos\n• Proteção: FLAG_SECURE e Auto-Lock ativo")
                    .setPositiveButton("OK", null)
                    .show();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    lockAndExitVault();
                }
            }
        });

        loadVaultIndexAsync();
    }

    /**
     * Exibe diálogo responsivo XML para redefinir a senha do cofre atualmente aberto.
     */
    private void showChangePasswordDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null);

        final EditText etNewPwd = view.findViewById(R.id.etNewVaultPassword);
        final EditText etConfirmPwd = view.findViewById(R.id.etConfirmVaultPassword);
        MaterialButton btnCancel = view.findViewById(R.id.btnCancelChangePwd);
        MaterialButton btnConfirm = view.findViewById(R.id.btnConfirmChangePwd);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String newPwd = etNewPwd.getText().toString().trim();
            String confirmPwd = etConfirmPwd.getText().toString().trim();

            if (!newPwd.equals(confirmPwd)) {
                Toast.makeText(this, "As senhas digitadas não coincidem.", Toast.LENGTH_LONG).show();
                return;
            }

            int result = CryptoManager.changeActiveVaultPassword(this, newPwd);
            if (result == CryptoManager.PWD_CHANGE_SUCCESS) {
                Toast.makeText(this, "Senha deste cofre redefinida com sucesso!", Toast.LENGTH_LONG).show();
                dialog.dismiss();
            } else if (result == CryptoManager.PWD_CHANGE_CONFLICT_OTHER_VAULT) {
                Toast.makeText(this, "Escolha uma senha diferente da usada no outro cofre.", Toast.LENGTH_LONG).show();
            } else if (result == CryptoManager.PWD_CHANGE_INVALID_FORMAT) {
                Toast.makeText(this, "Use ao menos 4 dígitos numéricos (diferente de 0000).", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Erro ao atualizar a senha do cofre.", Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            int maxWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void registerActivityLaunchers() {
        pickMediaLauncher = registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(25),
                uris -> {
                    isLaunchingSystemDialog = false;
                    if (uris != null && !uris.isEmpty()) {
                        importSelectedMedia(uris);
                    }
                }
        );

        deleteOriginalsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    isLaunchingSystemDialog = false;
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Toast.makeText(this, "Arquivos originais removidos da galeria pública.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void launchMediaPicker() {
        isLaunchingSystemDialog = true;
        pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                .build());
    }

    private void loadVaultIndexAsync() {
        if (!VaultSession.getInstance().isActive()) {
            lockAndExitVault();
            return;
        }

        File vaultDir = VaultSession.getInstance().getVaultDirectory();
        SecretKey key = VaultSession.getInstance().getSecretKey();

        ioExecutor.execute(() -> {
            List<MediaItem> loaded = CryptoManager.loadMediaIndex(vaultDir, key);
            Collections.sort(loaded, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));

            runOnUiThread(() -> {
                mediaItems.clear();
                mediaItems.addAll(loaded);
                mediaAdapter.notifyDataSetChanged();
                updateEmptyState();
            });
        });
    }

    private void importSelectedMedia(List<Uri> uris) {
        if (!VaultSession.getInstance().isActive()) {
            lockAndExitVault();
            return;
        }

        showLoading("Criptografando mídias com AES-256-GCM...");
        File vaultDir = VaultSession.getInstance().getVaultDirectory();
        SecretKey secretKey = VaultSession.getInstance().getSecretKey();

        ioExecutor.execute(() -> {
            int importedCount = 0;
            List<Uri> successfullyImportedUris = new ArrayList<>();
            List<MediaItem> currentIndex = CryptoManager.loadMediaIndex(vaultDir, secretKey);

            for (Uri uri : uris) {
                try {
                    String originalName = queryFileName(uri);
                    String mimeType = getContentResolver().getType(uri);
                    if (mimeType == null) {
                        mimeType = "image/jpeg";
                    }

                    String uniqueId = UUID.randomUUID().toString();
                    String encryptedFileName = uniqueId.replace("-", "") + ".bin";
                    File destFile = new File(vaultDir, encryptedFileName);

                    try (InputStream is = getContentResolver().openInputStream(uri)) {
                        if (is != null) {
                            CryptoManager.encryptStreamToFile(is, destFile, secretKey);
                            MediaItem newItem = new MediaItem(
                                    uniqueId,
                                    encryptedFileName,
                                    originalName,
                                    mimeType,
                                    System.currentTimeMillis()
                            );
                            currentIndex.add(0, newItem);
                            successfullyImportedUris.add(uri);
                            importedCount++;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            CryptoManager.saveMediaIndex(vaultDir, secretKey, currentIndex);
            final int finalCount = importedCount;

            runOnUiThread(() -> {
                hideLoading();
                loadVaultIndexAsync();
                if (finalCount > 0) {
                    Toast.makeText(this, finalCount + " arquivo(s) protegido(s) no cofre.", Toast.LENGTH_SHORT).show();
                    sanitizePublicOriginals(successfullyImportedUris);
                }
            });
        });
    }

    private void sanitizePublicOriginals(List<Uri> importedUris) {
        List<Uri> remainingForMediaStoreRequest = new ArrayList<>();
        ContentResolver resolver = getContentResolver();

        for (Uri uri : importedUris) {
            boolean deletedDirectly = false;
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    deletedDirectly = DocumentsContract.deleteDocument(resolver, uri);
                }
                if (!deletedDirectly) {
                    int rows = resolver.delete(uri, null, null);
                    deletedDirectly = (rows > 0);
                }
            } catch (Exception ignored) {
            }

            if (!deletedDirectly) {
                Uri mediaStoreUri = resolveToMediaStoreUri(uri);
                if (mediaStoreUri != null) {
                    remainingForMediaStoreRequest.add(mediaStoreUri);
                }
            }
        }

        if (!remainingForMediaStoreRequest.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                PendingIntent deletePendingIntent = MediaStore.createDeleteRequest(resolver, remainingForMediaStoreRequest);
                isLaunchingSystemDialog = true;
                IntentSenderRequest request = new IntentSenderRequest.Builder(deletePendingIntent.getIntentSender()).build();
                deleteOriginalsLauncher.launch(request);
            } catch (Exception ignored) {
                isLaunchingSystemDialog = false;
            }
        }
    }

    private Uri resolveToMediaStoreUri(Uri uri) {
        try {
            String authority = uri.getAuthority();
            if ("media".equals(authority)) {
                List<String> segments = uri.getPathSegments();
                if (segments.size() >= 2) {
                    long id = Long.parseLong(segments.get(segments.size() - 1));
                    String mime = getContentResolver().getType(uri);
                    if (mime != null && mime.startsWith("video/")) {
                        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    } else {
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onMediaClick(int position, MediaItem item) {
        isNavigatingToViewer = true;
        Intent intent = new Intent(this, MediaViewerActivity.class);
        intent.putExtra("INITIAL_POSITION", position);
        startActivity(intent);
    }

    @Override
    public void onMediaLongClick(MediaItem item) {
        String[] options = {
                "Restaurar para a Galeria Pública",
                "Excluir Permanentemente do Cofre"
        };

        new AlertDialog.Builder(this)
                .setTitle(item.getOriginalName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        restoreMediaToPublicGallery(item);
                    } else if (which == 1) {
                        confirmPermanentDeletion(item);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void restoreMediaToPublicGallery(MediaItem item) {
        if (!VaultSession.getInstance().isActive()) {
            lockAndExitVault();
            return;
        }

        showLoading("Restaurando arquivo para a galeria...");
        File vaultDir = VaultSession.getInstance().getVaultDirectory();
        SecretKey secretKey = VaultSession.getInstance().getSecretKey();

        ioExecutor.execute(() -> {
            boolean success = false;
            try {
                File encryptedFile = new File(vaultDir, item.getEncryptedFileName());
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, item.getOriginalName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, item.getMimeType());

                Uri collectionUri = item.isVideo()
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            (item.isVideo() ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Restaurados");
                }

                Uri targetUri = resolver.insert(collectionUri, values);
                if (targetUri != null) {
                    try (OutputStream os = resolver.openOutputStream(targetUri)) {
                        if (os != null) {
                            CryptoManager.decryptFileToStream(encryptedFile, os, secretKey);
                            success = true;
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                hideLoading();
                Toast.makeText(this,
                        finalSuccess ? "Mídia restaurada na galeria pública!" : "Erro ao restaurar mídia.",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmPermanentDeletion(MediaItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir Permanentemente")
                .setMessage("Deseja destruir \"" + item.getOriginalName() + "\" de forma irreversível?")
                .setPositiveButton("Excluir", (dialog, which) -> deleteMediaPermanently(item))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void deleteMediaPermanently(MediaItem item) {
        if (!VaultSession.getInstance().isActive()) {
            lockAndExitVault();
            return;
        }

        showLoading("Destruindo arquivo de forma segura...");
        File vaultDir = VaultSession.getInstance().getVaultDirectory();
        SecretKey secretKey = VaultSession.getInstance().getSecretKey();

        ioExecutor.execute(() -> {
            File targetFile = new File(vaultDir, item.getEncryptedFileName());
            if (targetFile.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rws")) {
                    long length = Math.min(targetFile.length(), 65536);
                    byte[] zeros = new byte[(int) length];
                    raf.seek(0);
                    raf.write(zeros);
                } catch (Exception ignored) {
                }
                targetFile.delete();
            }

            List<MediaItem> currentIndex = CryptoManager.loadMediaIndex(vaultDir, secretKey);
            for (int i = 0; i < currentIndex.size(); i++) {
                if (currentIndex.get(i).getId().equals(item.getId())) {
                    currentIndex.remove(i);
                    break;
                }
            }
            CryptoManager.saveMediaIndex(vaultDir, secretKey, currentIndex);

            runOnUiThread(() -> {
                hideLoading();
                loadVaultIndexAsync();
                Toast.makeText(this, "Arquivo destruído permanentemente.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private String queryFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (result == null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(getContentResolver().getType(uri));
            result = "midia_" + System.currentTimeMillis() + "." + (ext != null ? ext : "jpg");
        }
        return result;
    }

    private void updateEmptyState() {
        if (mediaItems.isEmpty()) {
            emptyStateContainer.setVisibility(View.VISIBLE);
            rvMediaGrid.setVisibility(View.GONE);
        } else {
            emptyStateContainer.setVisibility(View.GONE);
            rvMediaGrid.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(String message) {
        tvLoadingMessage.setText(message);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isNavigatingToViewer = false;
        if (!VaultSession.getInstance().isActive()) {
            lockAndExitVault();
        } else {
            loadVaultIndexAsync();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isLaunchingSystemDialog && !isNavigatingToViewer && !isChangingConfigurations()) {
            lockAndExitVault();
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaAdapter != null) {
            mediaAdapter.releaseMemory();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void lockAndExitVault() {
        if (mediaAdapter != null) {
            mediaAdapter.releaseMemory();
        }
        VaultSession.getInstance().clearSession();
        finish();
    }
}
