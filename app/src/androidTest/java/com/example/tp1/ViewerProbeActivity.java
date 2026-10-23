package com.example.tp1;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Separate test-APK UID, no storage permissions. Receives a real External Storage document. */
public class ViewerProbeActivity extends Activity {
    private boolean allowed(Uri uri, int flags) {
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED;
    }
    private void verify(boolean condition) { if (!condition) throw new IllegalStateException("Grant assertion failed"); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String report;
        try {
            Intent intent = getIntent();
            Uri uri = intent.getData();
            Uri root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
            Uri sibling = DocumentsContract.buildDocumentUriUsingTree(uri, "sibling");
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                verify(new String(input.readAllBytes(), StandardCharsets.UTF_8).equals("TP1 viewer grant proof"));
            }
            verify(allowed(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION));
            verify(!allowed(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            verify(!allowed(root, Intent.FLAG_GRANT_READ_URI_PERMISSION));
            verify(!allowed(sibling, Intent.FLAG_GRANT_READ_URI_PERMISSION));
            verify("text/plain".equals(intent.getType()));
            verify(intent.getClipData().getItemCount() == 1 && uri.equals(intent.getClipData().getItemAt(0).getUri()));
            verify((intent.getFlags() & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) == 0);
            report = "TP1 viewer proof: read OK; write denied; root denied; sibling denied; MIME OK; ClipData OK; uid=" + Process.myUid();
        } catch (Exception error) { report = "TP1 viewer proof FAILED: " + error; }
        TextView text = new TextView(this);
        text.setText(report);
        text.setTextSize(20);
        text.setPadding(24, 80, 24, 24);
        setContentView(text);
    }
}
