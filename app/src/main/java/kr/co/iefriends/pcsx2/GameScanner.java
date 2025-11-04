package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import kr.co.iefriends.pcsx2.util.DebugLog;

public class GameScanner {
    static final String[] EXTS = new String[]{".iso", ".img", ".bin", ".cso", ".zso", ".chd", ".gz"};
    static List<GameEntry> scanFolder(Context ctx, Uri treeUri) {
        List<GameEntry> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            scanChildren(cr, treeUri, rootId, out, 0, 3);
        } catch (Exception ignored) { }
        return out;
    }

    static List<String> debugList(Context ctx, Uri treeUri) {
        List<String> out = new ArrayList<>();
        try {
            ContentResolver cr = ctx.getContentResolver();
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            debugChildren(cr, treeUri, rootId, out, 0, 3, "/");
        } catch (Exception e) {
            out.add("Error: " + e.getMessage());
        }
        return out;
    }

    private static void scanChildren(ContentResolver cr, Uri treeUri, String parentDocId,
                                     List<GameEntry> out, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (Cursor c = cr.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                    scanChildren(cr, treeUri, docId, out, depth + 1, maxDepth);
                    continue;
                }
                if (name == null) name = "Unknown";
                String lower = name.toLowerCase();
                boolean matchExt = false;
                for (String ext : EXTS) {
                    if (lower.endsWith(ext)) {
                        matchExt = true; break;
                    }
                }
                boolean matchMime = false;
                if (mime != null) {
                    String lm = mime.toLowerCase();
                    if (lm.contains("iso9660") || lm.equals("application/x-iso9660-image")) matchMime = true;
                }
                boolean match = matchExt || matchMime;
                if (!match) continue;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                GameEntry e = new GameEntry(name, doc);
                String ft = e.fileTitleNoExt();
                String s = parseSerialFromString(ft);
                if (s != null) e.serial = s;
                String lowerName = name != null ? name.toLowerCase() : "";
                if (e.serial == null && (lowerName.endsWith(".iso")
                        || lowerName.endsWith(".img")
                        || lowerName.endsWith(".cso")
                        || lowerName.endsWith(".zso"))) {
                    try {
                        String isoSerial = tryExtractIsoSerial(cr, doc);
                        if (isoSerial != null) e.serial = isoSerial;
                    } catch (Throwable t) {
                        try {
                            DebugLog.d("ISO", "Serial parse failed: " + t.getMessage());
                        } catch (Throwable ignored) { }
                    }
                }
                if (e.serial == null && lowerName.endsWith(".bin")) {
                    try {
                        String quick = tryExtractBinSerialQuick(cr, doc);
                        if (quick != null) e.serial = quick;
                    } catch (Throwable t) {
                        try {
                            DebugLog.d("BIN", "Quick serial scan failed: " + t.getMessage());
                        } catch (Throwable ignored) { }
                    }
                }
                out.add(e);
            }
        } catch (Exception ignored) { }
    }

    private static void debugChildren(ContentResolver cr, Uri treeUri, String parentDocId,
                                       List<String> out, int depth, int maxDepth, String pathPrefix) {
        if (depth > maxDepth) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (Cursor c = cr.query(children, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String docId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                String display = pathPrefix + (name != null ? name : "<null>") + (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR) ? "/" : "");
                boolean dir = mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR);
                boolean accept = false;
                if (!dir && name != null) {
                    String lower = name.toLowerCase();
                    boolean matchExt = false;
                    for (String ext : EXTS) {
                        if (lower.endsWith(ext)) {
                            matchExt = true; break;
                        }
                    }
                    boolean matchMime = false;
                    if (mime != null) {
                        String lm = mime.toLowerCase();
                        if (lm.contains("iso9660") || lm.equals("application/x-iso9660-image")) matchMime = true;
                    }
                    accept = matchExt || matchMime;
                }
                out.add("[" + (mime == null ? "null" : mime) + "] " + display + (dir ? "" : (accept ? "  -> accepted" : "  -> skipped")));
                if (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                    debugChildren(cr, treeUri, docId, out, depth + 1, maxDepth, display);
                }
            }
        } catch (Exception e) {
            out.add("Error listing: " + e.getMessage());
        }
    }
    static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(0, i) : name;
    }

    static String parseSerialFromString(String s) {
        if (s == null) return null;
        Pattern p = Pattern.compile(
                "(S[CL](?:ES|US|PS|CS)?[-_]?[0-9]{3,5}(?:\\.[0-9]{2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(s);
        if (m.find()) {
            String v = m.group(1).toUpperCase();
            v = v.replace('_', '-');
            v = v.replace(".", "");
            v = v.replaceAll("^([A-Z]+)([0-9])", "$1-$2");
            return v;
        }
        return null;
    }

    static String tryExtractIsoSerial(ContentResolver cr, Uri uri) throws IOException {
        final int SECTOR = 2048;
        byte[] pvd = readRange(cr, uri, 16L * SECTOR, SECTOR);
        if (pvd == null || pvd.length < SECTOR) return null;
        if (pvd[0] != 0x01
                || pvd[1] != 'C'
                || pvd[2] != 'D'
                || pvd[3] != '0'
                || pvd[4] != '0'
                || pvd[5] != '1') {
            return null;
        }
        int rootLBA = u32le(pvd, 156 + 2);
        int rootSize = u32le(pvd, 156 + 10);
        if (rootLBA <= 0 || rootSize <= 0 || rootSize > 512 * 1024) rootSize = 64 * 1024;
        byte[] dir = readRange(cr, uri, (long) rootLBA * SECTOR, rootSize);
        if (dir == null) return null;
        int off = 0;
        while (off < dir.length) {
            int len = u8(dir, off);
            if (len == 0) {
                int next = ((off / SECTOR) + 1) * SECTOR;
                if (next <= off) break;
                off = next;
                continue;
            }
            if (off + len > dir.length) break;
            int lba = u32le(dir, off + 2);
            int size = u32le(dir, off + 10);
            int nameLen = u8(dir, off + 32);
            int namePos = off + 33;
            if (namePos + nameLen <= dir.length && nameLen > 0) {
                String name = new String(dir, namePos, nameLen, StandardCharsets.US_ASCII);
                if (!(nameLen == 1 && (dir[namePos] == 0 || dir[namePos] == 1))) {
                    String norm = name;
                    int semi = norm.indexOf(';');
                    if (semi >= 0) norm = norm.substring(0, semi);
                    if ("SYSTEM.CNF".equalsIgnoreCase(norm)) {
                        int readSize = Math.min(size, 4096);
                        byte[] cnf = readRange(cr, uri, (long) lba * SECTOR, readSize);
                        if (cnf != null) {
                            String txt = new String(cnf, StandardCharsets.US_ASCII);
                            Matcher m = Pattern.compile(
                                    "BOOT\\d*\\s*=\\s*[^\\\\\\r\\n]*\\\\([A-Z0-9_\\.]+)",
                                    Pattern.CASE_INSENSITIVE).matcher(txt);
                            if (m.find()) {
                                String bootElf = m.group(1);
                                String serial = parseSerialFromString(bootElf);
                                if (serial != null) return serial;
                            }
                        }
                        break;
                    }
                }
            }
            off += len;
        }
        return null;
    }

    static String tryExtractBinSerialQuick(ContentResolver cr, Uri uri) throws IOException {
        final int MAX = 8 * 1024 * 1024;
        byte[] buf;
        try (InputStream in = CsoUtils.openInputStream(cr, uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(MAX, 1 << 20));
            byte[] tmp = new byte[64 * 1024];
            int total = 0;
            while (total < MAX) {
                int want = Math.min(tmp.length, MAX - total);
                int r = in.read(tmp, 0, want);
                if (r <= 0) break;
                bos.write(tmp, 0, r);
                total += r;
            }
            buf = bos.toByteArray();
        }
        if (buf == null || buf.length == 0) return null;
        String txt = new String(buf, StandardCharsets.US_ASCII);
        Matcher m = Pattern.compile(
                "BOOT\\d*\\s*=\\s*[^\\\\\\r\\n]*\\\\([A-Z0-9_\\.]+)",
                Pattern.CASE_INSENSITIVE).matcher(txt);
        if (m.find()) {
            String bootElf = m.group(1);
            String serial = parseSerialFromString(bootElf);
            if (serial != null) return serial;
        }
        String s2 = parseSerialFromString(txt);
        return s2;
    }

    private static int u8(byte[] a, int i) {
        return (i >= 0 && i < a.length) ? (a[i] & 0xFF) : 0;
    }
    private static int u32le(byte[] a, int i) {
        if (i + 3 >= a.length) return 0;
        return (a[i] & 0xFF) | ((a[i + 1] & 0xFF) << 8) | ((a[i + 2] & 0xFF) << 16) | ((a[ i + 3] & 0xFF) << 24);
    }
    private static byte[] readRange(ContentResolver cr, Uri uri, long offset, int size) throws IOException {
        if (size <= 0) return null;
        if (size > 2 * 1024 * 1024) size = 2 * 1024 * 1024;
        byte[] csoBytes = CsoUtils.readRange(cr, uri, offset, size);
        if (csoBytes != null) {
            return csoBytes;
        }
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            long toSkip = offset;
            byte[] skipBuf = new byte[8192];
            while (toSkip > 0) {
                long skipped = in.skip(toSkip);
                if (skipped <= 0) {
                    int r = in.read(skipBuf, 0, (int) Math.min(skipBuf.length, toSkip));
                    if (r <= 0) break;
                    toSkip -= r;
                } else {
                    toSkip -= skipped;
                }
            }
            byte[] buf = new byte[size];
            int off2 = 0;
            while (off2 < size) {
                int r = in.read(buf, off2, size - off2);
                if (r <= 0) break;
                off2 += r;
            }
            if (off2 == 0) return null;
            if (off2 < size) return Arrays.copyOf(buf, off2);
            return buf;
        }
    }

    static final class CsoUtils {
        private static final int MAGIC_CISO = 0x4F534943;
        private static final int MAGIC_ZISO = 0x4F53495A;

        private CsoUtils() { }

        @Nullable
        static byte[] readRange(ContentResolver cr, Uri uri, long offset, int size) {
            CsoReader reader = null;
            try {
                reader = CsoReader.open(cr, uri);
                if (reader == null) {
                    return null;
                }
                return reader.readRange(offset, size);
            } catch (Exception ignored) {
                return null;
            } finally {
                closeQuietly(reader);
            }
        }

        @Nullable
        static InputStream openInputStream(ContentResolver cr, Uri uri) throws IOException {
            CsoReader reader = CsoReader.open(cr, uri);
            if (reader == null) {
                return cr.openInputStream(uri);
            }
            return new CsoInputStream(reader);
        }

        private static void closeQuietly(@Nullable Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) { }
        }

        private static final class CsoReader implements Closeable {
            private final ParcelFileDescriptor descriptor;
            private final FileInputStream inputStream;
            private final FileChannel channel;
            private final long uncompressedSize;
            private final int blockSize;
            private final int alignShift;
            private final int[] indexTable;
            private final int blockCount;

            private CsoReader(ParcelFileDescriptor descriptor, FileInputStream inputStream, FileChannel channel,
                              long uncompressedSize, int blockSize, int alignShift, int[] indexTable) {
                this.descriptor = descriptor;
                this.inputStream = inputStream;
                this.channel = channel;
                this.uncompressedSize = uncompressedSize;
                this.blockSize = blockSize;
                this.alignShift = alignShift;
                this.indexTable = indexTable;
                this.blockCount = indexTable.length - 1;
            }

            static CsoReader open(ContentResolver cr, Uri uri) throws IOException {
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                if (pfd == null) {
                    return null;
                }
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(pfd.getFileDescriptor());
                    FileChannel channel = fis.getChannel();
                    ByteBuffer header = ByteBuffer.allocate(0x18).order(ByteOrder.LITTLE_ENDIAN);
                    if (channel.read(header) < 0x18) {
                        closeQuietly(fis);
                        closeQuietly(pfd);
                        return null;
                    }
                    header.flip();
                    int magic = header.getInt();
                    if (magic != MAGIC_CISO && magic != MAGIC_ZISO) {
                        closeQuietly(fis);
                        closeQuietly(pfd);
                        return null;
                    }
                    int headerSize = header.getInt();
                    long uncompressedSize = header.getLong();
                    int blockSize = header.getInt();
                    header.get();
                    int align = header.get() & 0xFF;
                    header.get();
                    header.get();
                    if (blockSize <= 0 || uncompressedSize <= 0 || headerSize < 0x18) {
                        closeQuietly(fis);
                        closeQuietly(pfd);
                        return null;
                    }
                    int entryCount = (headerSize - 0x18) / 4;
                    if (entryCount <= 1) {
                        closeQuietly(fis);
                        closeQuietly(pfd);
                        return null;
                    }
                    int[] table = new int[entryCount];
                    ByteBuffer indexBuffer = ByteBuffer.allocate(entryCount * 4).order(ByteOrder.LITTLE_ENDIAN);
                    if (channel.read(indexBuffer) < entryCount * 4) {
                        closeQuietly(fis);
                        closeQuietly(pfd);
                        return null;
                    }
                    indexBuffer.flip();
                    for (int i = 0; i < entryCount; i++) {
                        table[i] = indexBuffer.getInt();
                    }
                    return new CsoReader(pfd, fis, channel, uncompressedSize, blockSize, align, table);
                } catch (Exception e) {
                    closeQuietly(fis);
                    closeQuietly(pfd);
                    throw e;
                }
            }

            byte[] readRange(long offset, int size) throws IOException {
                if (size <= 0 || offset < 0 || offset >= uncompressedSize) {
                    return null;
                }
                int cappedSize = (int) Math.min(size, uncompressedSize - offset);
                byte[] output = new byte[cappedSize];
                byte[] blockBuffer = new byte[blockSize];
                int startBlock = (int) (offset / blockSize);
                int endBlock = Math.min(blockCount, (int) Math.ceil((offset + cappedSize) / (double) blockSize));
                int outOffset = 0;
                int offsetInBlock = (int) (offset % blockSize);
                long remaining = cappedSize;
                for (int block = startBlock; block < endBlock && remaining > 0; block++) {
                    int produced = readBlockInto(block, blockBuffer);
                    if (produced <= 0) {
                        break;
                    }
                    int start = (block == startBlock) ? offsetInBlock : 0;
                    if (start >= produced) {
                        continue;
                    }
                    int copyLength = (int) Math.min(produced - start, remaining);
                    System.arraycopy(blockBuffer, start, output, outOffset, copyLength);
                    outOffset += copyLength;
                    remaining -= copyLength;
                }
                if (outOffset == 0) {
                    return null;
                }
                if (outOffset < output.length) {
                    return Arrays.copyOf(output, outOffset);
                }
                return output;
            }

            int readBlockInto(int blockIndex, byte[] dest) throws IOException {
                if (blockIndex < 0 || blockIndex >= blockCount) {
                    return -1;
                }
                long startOffset = (long) (indexTable[blockIndex] & 0x7FFFFFFFL) << alignShift;
                long endOffset = (long) (indexTable[blockIndex + 1] & 0x7FFFFFFFL) << alignShift;
                boolean isPlain = (indexTable[blockIndex] & 0x80000000) != 0;
                int compressedSize = (int) Math.max(0, endOffset - startOffset);
                int expectedSize = (int) Math.min(blockSize, uncompressedSize - ((long) blockIndex * blockSize));
                if (expectedSize <= 0) {
                    return 0;
                }
                if (compressedSize == 0) {
                    Arrays.fill(dest, 0, expectedSize, (byte) 0);
                    return expectedSize;
                }
                byte[] compressed = new byte[compressedSize];
                ByteBuffer buffer = ByteBuffer.wrap(compressed);
                channel.position(startOffset);
                int readTotal = 0;
                while (buffer.hasRemaining()) {
                    int r = channel.read(buffer);
                    if (r <= 0) {
                        break;
                    }
                    readTotal += r;
                }
                if (readTotal != compressedSize) {
                    return -1;
                }
                if (isPlain) {
                    int toCopy = Math.min(expectedSize, compressedSize);
                    System.arraycopy(compressed, 0, dest, 0, toCopy);
                    if (toCopy < expectedSize) {
                        Arrays.fill(dest, toCopy, expectedSize, (byte) 0);
                    }
                    return expectedSize;
                }
                Inflater inflater = new Inflater(true);
                try {
                    inflater.setInput(compressed);
                    int total = 0;
                    while (!inflater.finished() && total < expectedSize) {
                        int r = inflater.inflate(dest, total, expectedSize - total);
                        if (r <= 0) {
                            if (inflater.needsInput() || inflater.finished()) {
                                break;
                            }
                        } else {
                            total += r;
                        }
                    }
                    if (total <= 0) {
                        Arrays.fill(dest, 0, expectedSize, (byte) 0);
                        return expectedSize;
                    }
                    return total;
                } catch (Exception ignored) {
                    return -1;
                } finally {
                    inflater.end();
                }
            }

            int getBlockCount() {
                return blockCount;
            }

            int getBlockSize() {
                return blockSize;
            }

            long getUncompressedSize() {
                return uncompressedSize;
            }

            @Override
            public void close() throws IOException {
                try {
                    channel.close();
                } finally {
                    try {
                        inputStream.close();
                    } finally {
                        descriptor.close();
                    }
                }
            }
        }

        private static final class CsoInputStream extends InputStream {
            private final CsoReader reader;
            private final byte[] blockBuffer;
            private int currentBlock = 0;
            private int blockPos = 0;
            private int blockLimit = 0;
            private long bytesRemaining;

            CsoInputStream(CsoReader reader) {
                this.reader = reader;
                this.blockBuffer = new byte[reader.getBlockSize()];
                this.bytesRemaining = reader.getUncompressedSize();
            }

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                int r = read(single, 0, 1);
                if (r <= 0) {
                    return -1;
                }
                return single[0] & 0xFF;
            }

            @Override
            public int read(@NonNull byte[] b, int off, int len) throws IOException {
                if (b == null) {
                    throw new NullPointerException();
                }
                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException();
                }
                if (len == 0) return 0;
                if (bytesRemaining <= 0) return -1;

                int total = 0;
                while (len > 0 && bytesRemaining > 0) {
                    if (blockPos >= blockLimit) {
                        if (currentBlock >= reader.getBlockCount()) {
                            break;
                        }
                        blockLimit = reader.readBlockInto(currentBlock, blockBuffer);
                        currentBlock++;
                        blockPos = 0;
                        if (blockLimit <= 0) {
                            break;
                        }
                    }
                    int available = blockLimit - blockPos;
                    int copy = Math.min(len, available);
                    copy = (int) Math.min(copy, bytesRemaining);
                    if (copy <= 0) {
                        break;
                    }
                    System.arraycopy(blockBuffer, blockPos, b, off, copy);
                    off += copy;
                    len -= copy;
                    total += copy;
                    blockPos += copy;
                    bytesRemaining -= copy;
                }
                return total > 0 ? total : -1;
            }

            @Override
            public void close() throws IOException {
                reader.close();
            }
        }
    }
}
