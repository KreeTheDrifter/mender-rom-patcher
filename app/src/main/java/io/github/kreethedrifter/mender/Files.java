package io.github.kreethedrifter.mender;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.CRC32;

/** Reading and writing files picked with the system file picker. */
final class Files {

    static final long MAX_SIZE = 256L * 1024 * 1024;

    static final class TooBigException extends IOException {
        TooBigException() {
            super("too big");
        }
    }

    /** Name and size of a picked file. */
    static final class Meta {
        final String name;
        final long size;

        Meta(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private Files() {}

    static Meta meta(ContentResolver cr, Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // some providers don't support queries; fall back below
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            name = last != null ? last : "file";
        }
        return new Meta(name, size);
    }

    /** Reads a whole file. knownSize (from {@link #meta}) avoids a second copy in memory; pass -1 if unknown. */
    static byte[] read(ContentResolver cr, Uri uri, long knownSize) throws IOException {
        if (knownSize > MAX_SIZE) throw new TooBigException();
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            if (knownSize > 0) {
                byte[] data = new byte[(int) knownSize];
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                // size was right: done; otherwise fall through to a plain read of the rest
                if (off == data.length && in.read() < 0) return data;
                if (off < data.length) {
                    byte[] shorter = new byte[off];
                    System.arraycopy(data, 0, shorter, 0, off);
                    return shorter;
                }
                throw new IOException("file changed while reading");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SIZE) throw new TooBigException();
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** CRC32 and size of a file, read as a stream (doesn't hold the whole file in memory). */
    static long[] crcAndSize(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            CRC32 crc = new CRC32();
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                total += n;
                if (total > MAX_SIZE) throw new TooBigException();
            }
            return new long[] {crc.getValue(), total};
        }
    }

    static void write(ContentResolver cr, Uri uri, byte[] data) throws IOException {
        // "wt" truncates when overwriting an existing file
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("no stream");
            out.write(data);
        }
    }

    static String sizeText(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    static String crcText(long crc) {
        return String.format(Locale.US, "%08X", crc);
    }

    /** "Game (USA).gba" -> "Game (USA)" */
    static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "Game (USA).gba" -> ".gba" (or "" if there is none) */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }
}
