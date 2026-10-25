package com.example.tp1;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Java keeps these standalone test-APK components independent of TP1's Kotlin runtime.
public class BrowserFixtureProvider extends DocumentsProvider {
    private final List<String> visited = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean fileUnavailable;
    private volatile byte[] extractionBytes = "name,value\nTP1,fixture\n".getBytes(StandardCharsets.UTF_8);
    private volatile String extractionName = "fixture.csv";
    private volatile String extractionMime = "text/csv";
    private volatile long extractionSize = -1;
    private volatile boolean extractionSlow;
    private static final List<String> CHILDREN = Arrays.asList("slow", "error", "null", "missing-id", "pending", "gone", "file", "sibling", "unviewable");
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[] {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.addRow(new Object[] {"fixture", "root", "TP1 pruebas", Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD});
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) {
        if (id.equals("extract")) {
            MatrixCursor cursor = new MatrixCursor(new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED});
            cursor.addRow(new Object[] {id, extractionName, extractionMime, extractionSize < 0 ? null : extractionSize, 1L});
            return cursor;
        }
        if (fileUnavailable && id.equals("file")) throw new IllegalStateException("Temporary fixture provider failure");
        return rows(id.equals("gone") ? Collections.emptyList() : Collections.singletonList(id));
    }

    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) throws FileNotFoundException {
        visited.add(id);
        if (id.equals("root")) return rows(CHILDREN);
        if (id.equals("slow")) { SystemClock.sleep(1200); return rows(Collections.singletonList("file")); }
        if (id.equals("null")) return null;
        if (id.equals("gone")) throw new FileNotFoundException();
        if (id.equals("missing-id")) {
            MatrixCursor cursor = new MatrixCursor(new String[] {Document.COLUMN_DISPLAY_NAME});
            cursor.addRow(new Object[] {"Sin identidad"});
            return cursor;
        }
        MatrixCursor cursor = rows(Collections.emptyList());
        Bundle extras = new Bundle();
        if (id.equals("error")) extras.putString(DocumentsContract.EXTRA_ERROR, "fixture error");
        if (id.equals("pending")) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
        cursor.setExtras(extras);
        return cursor;
    }

    private MatrixCursor rows(List<String> ids) {
        MatrixCursor cursor = new MatrixCursor(new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE});
        for (String id : ids) cursor.addRow(new Object[] {id, id,
            id.equals("unviewable") ? "application/x-tp1-no-viewer" :
            id.equals("file") || id.equals("sibling") ? "application/x-tp1-probe" : Document.MIME_TYPE_DIR});
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        return parent.equals("root") && CHILDREN.contains(child) || parent.equals("slow") && child.equals("file");
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (id.equals("extract") && mode.equals("r")) {
            byte[] bytes = extractionBytes;
            if (extractionSlow) {
                return openPipeHelper(DocumentsContract.buildDocumentUri("com.example.tp1.test.documents", id),
                    extractionMime, null, bytes, (descriptor, uri, mime, options, data) -> {
                        try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                            for (int offset = 0; offset < data.length; offset += 4096) {
                                output.write(data, offset, Math.min(4096, data.length - offset));
                                SystemClock.sleep(20);
                            }
                        } catch (IOException ignored) {
                            // The reader closes the pipe when an extraction is cancelled or exceeds its limit.
                        }
                    });
            }
            File file = new File(getContext().getCacheDir(), "extraction-proof");
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!mode.equals("r") || !(id.equals("file") || id.equals("sibling") || id.equals("unviewable"))) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), "viewer-proof.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("TP1 viewer grant proof".getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (method.equals("fixture-extraction")) {
            extractionBytes = extras.getByteArray("bytes");
            extractionName = extras.getString("name", "fixture.csv");
            extractionMime = extras.getString("mime", "text/csv");
            extractionSize = extras.getLong("size", -1);
            extractionSlow = extras.getBoolean("slow", false);
            android.net.Uri uri = DocumentsContract.buildDocumentUri("com.example.tp1.test.documents", "extract");
            getContext().grantUriPermission("com.example.tp1", uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            result.putString("uri", uri.toString());
            return result;
        }
        if (method.equals("fixture-extraction-revoke")) {
            getContext().revokeUriPermission(DocumentsContract.buildDocumentUri("com.example.tp1.test.documents", "extract"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return result;
        }
        if (method.equals("fixture-authorize")) {
            boolean file = "file".equals(arg);
            android.net.Uri uri = file ? DocumentsContract.buildDocumentUri("com.example.tp1.test.documents", "file") :
                DocumentsContract.buildTreeDocumentUri("com.example.tp1.test.documents", "root");
            getContext().grantUriPermission("com.example.tp1", uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                (file ? 0 : Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            result.putString("uri", uri.toString());
            fileUnavailable = false;
            visited.clear();
            return result;
        }
        if (method.equals("fixture-file-unavailable")) {
            fileUnavailable = Boolean.parseBoolean(arg);
            return result;
        }
        if (method.equals("fixture-visits")) {
            synchronized (visited) { result.putStringArrayList("visits", new ArrayList<>(visited)); }
            return result;
        }
        return super.call(method, arg, extras);
    }
}
