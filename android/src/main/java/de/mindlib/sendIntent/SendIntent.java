package de.mindlib.sendIntent;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@CapacitorPlugin()
public class SendIntent extends Plugin {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final List<File> pendingSharedFiles = Collections.synchronizedList(new ArrayList<>());

    @PluginMethod
    public void checkSendIntentReceived(PluginCall call) {
        List<File> createdFiles = new ArrayList<>();
        try {
            Intent intent = bridge.getActivity().getIntent();
            String action = intent != null ? intent.getAction() : null;
            String type = intent != null ? intent.getType() : null;
            if (intent == null || type == null ||
                (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action))) {
                call.reject("No valid share intent to process");
                finishShareActivityIfApplicable();
                return;
            }

            String sharedText = Intent.ACTION_SEND.equals(action)
                ? intent.getStringExtra(Intent.EXTRA_TEXT) : null;
            List<Uri> uris = collectSharedUris(intent, action);
            Map<String, Uri> uniqueUris = new LinkedHashMap<>();
            for (Uri uri : uris) {
                if (uri != null) uniqueUris.put(uri.toString(), uri);
            }
            if (uniqueUris.size() > ShareFileLimits.MAX_BATCH_FILES) {
                throw new IOException("Shared batch exceeds the maximum of " + ShareFileLimits.MAX_BATCH_FILES + " files");
            }

            long knownTotal = 0;
            for (Uri uri : uniqueUris.values()) {
                long size = queryFileSize(uri);
                if (size > ShareFileLimits.MAX_FILE_BYTES) throw new IOException("A shared file exceeds the 30 MiB limit");
                if (size >= 0) {
                    if (!ShareFileLimits.isWithinLimit(knownTotal, size, ShareFileLimits.MAX_BATCH_BYTES)) {
                        throw new IOException("Shared batch exceeds the 50 MiB limit");
                    }
                    knownTotal += size;
                }
            }

            JSObject result = new JSObject();
            result.put("title", intent.getStringExtra(Intent.EXTRA_SUBJECT));
            result.put("description", null);
            result.put("type", type);
            result.put("url", sharedText);
            JSArray additionalItems = new JSArray();
            int index = 0;
            long copiedTotal = 0;
            for (Uri uri : uniqueUris.values()) {
                File copied = copyUriToPrivateStorage(uri, ShareFileLimits.MAX_BATCH_BYTES - copiedTotal);
                createdFiles.add(copied);
                copiedTotal += copied.length();
                if (copiedTotal > ShareFileLimits.MAX_BATCH_BYTES) throw new IOException("Shared batch exceeds the 50 MiB limit");

                JSObject item = new JSObject();
                item.put("title", readFileName(uri));
                item.put("description", null);
                item.put("type", type);
                item.put("url", Uri.fromFile(copied).toString());
                if (index == 0 && sharedText == null) {
                    result.put("title", item.getString("title"));
                    result.put("url", item.getString("url"));
                } else {
                    additionalItems.put(item);
                }
                index++;
            }
            result.put("additionalItems", additionalItems);
            pendingSharedFiles.addAll(createdFiles);
            call.resolve(result);
        } catch (Exception error) {
            for (File file : createdFiles) {
                if (file.exists() && !file.delete()) {
                    android.util.Log.w("SendIntent", "Could not remove partial shared file: " + file.getName());
                }
            }
            call.reject(error.getMessage() != null ? error.getMessage() : "Unable to process shared content", error);
            finishShareActivityIfApplicable();
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        cleanupPendingSharedFiles();
        finishShareActivityIfApplicable();
        call.resolve();
    }

    private void cleanupPendingSharedFiles() {
        synchronized (pendingSharedFiles) {
            for (File file : pendingSharedFiles) {
                if (file.exists() && !file.delete()) {
                    android.util.Log.w("SendIntent", "Could not remove consumed shared file: " + file.getName());
                }
            }
            pendingSharedFiles.clear();
        }
    }

    private void finishShareActivityIfApplicable() {
        if (bridge != null && bridge.getActivity() instanceof SendIntentActivity) {
            bridge.getActivity().runOnUiThread(() -> {
                if (!bridge.getActivity().isFinishing()) bridge.getActivity().finish();
            });
        }
    }

    private List<Uri> collectSharedUris(Intent intent, String action) {
        List<Uri> uris = new ArrayList<>();
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }

        if (Intent.ACTION_SEND.equals(action)) {
            Uri extra = getSingleStreamUri(intent);
            if (extra != null) uris.add(extra);
        } else {
            ArrayList<android.os.Parcelable> extras = getMultipleStreamUris(intent);
            if (extras != null) {
                for (android.os.Parcelable value : extras) {
                    if (value instanceof Uri) uris.add((Uri) value);
                }
            }
        }
        return uris;
    }

    @SuppressWarnings("deprecation")
    private Uri getSingleStreamUri(Intent intent) {
        Object value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return value instanceof Uri ? (Uri) value : null;
    }

    @SuppressWarnings("deprecation")
    private ArrayList<android.os.Parcelable> getMultipleStreamUris(Intent intent) {
        return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }

    private long queryFileSize(Uri uri) throws IOException {
        try (Cursor cursor = getContext().getContentResolver().query(
            uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return -1;
            int column = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (column < 0 || cursor.isNull(column)) return -1;
            long size = cursor.getLong(column);
            if (size < 0) throw new IOException("Shared file reports an invalid size");
            return size;
        } catch (SecurityException error) {
            throw new IOException("Android denied access to a shared file", error);
        }
    }

    private String readFileName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver().query(
            uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception error) {
            android.util.Log.w("SendIntent", "Unable to read shared filename", error);
        }
        String segment = uri.getLastPathSegment();
        return segment != null ? segment : "shared-file";
    }

    private File copyUriToPrivateStorage(Uri uri, long remainingBatchBytes) throws IOException {
        long limit = Math.min(ShareFileLimits.MAX_FILE_BYTES, remainingBatchBytes);
        if (limit <= 0) throw new IOException("Shared batch exceeds the 50 MiB limit");

        File directory = getContext().getFilesDir();
        String token = UUID.randomUUID().toString();
        File temporary = new File(directory, ".share-" + token + ".part");
        File destination = new File(directory, "shared-" + token + safeExtension(readFileName(uri)));
        long copied = 0;

        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Android could not open a shared file");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (!ShareFileLimits.isWithinLimit(copied, count, limit)) {
                        throw new IOException(copied + count > ShareFileLimits.MAX_FILE_BYTES
                            ? "A shared file exceeds the 30 MiB limit"
                            : "Shared batch exceeds the 50 MiB limit");
                    }
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
                output.getFD().sync();
            }
            if (copied == 0) throw new IOException("The shared file is empty");
            if (!temporary.renameTo(destination)) throw new IOException("Could not finalize the shared file");
            return destination;
        } catch (Exception error) {
            if (temporary.exists() && !temporary.delete()) {
                android.util.Log.w("SendIntent", "Could not remove temporary shared file");
            }
            if (destination.exists() && !destination.delete()) {
                android.util.Log.w("SendIntent", "Could not remove incomplete shared file");
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Unable to copy shared file", error);
        }
    }

    private String safeExtension(String displayName) {
        if (displayName == null) return "";
        int dot = displayName.lastIndexOf('.');
        if (dot < 0 || dot == displayName.length() - 1) return "";
        String extension = displayName.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }
}
