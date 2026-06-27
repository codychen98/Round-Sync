package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal Matroska Cues scan: finds the earliest {@code CueClusterPosition} in a downloaded tail
 * slice so AV1 sparse Exo can fetch the cluster byte range missing from head-only prefixes.
 */
final class VideoMkvCueParser {

    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CUE_POINT = 0xBB;
    private static final int ID_CUE_CLUSTER_POSITION = 0xF1;

    private VideoMkvCueParser() {
    }

    /**
     * @return earliest cluster byte offset in the full file, or {@code -1} when not found
     */
    static long findEarliestClusterPosition(@NonNull File tailFile) throws IOException {
        byte[] data = readAllBytes(tailFile);
        if (data.length == 0) {
            return -1L;
        }
        int cuesOffset = indexOf(data, new byte[]{(byte) 0x1C, (byte) 0x53, (byte) 0xBB, (byte) 0x6B});
        if (cuesOffset < 0 || cuesOffset + 5 > data.length) {
            return -1L;
        }
        EbmlCursor cursor = new EbmlCursor(data);
        cursor.position = cuesOffset + 4;
        long cuesSize = cursor.readElementSize();
        if (cuesSize <= 0L) {
            return -1L;
        }
        long cuesEnd = Math.min(cursor.position + cuesSize, data.length);
        long earliest = Long.MAX_VALUE;
        while (cursor.position < cuesEnd) {
            int id = cursor.readElementId();
            if (id < 0) {
                break;
            }
            long size = cursor.readElementSize();
            if (size < 0L) {
                break;
            }
            long elementEnd = cursor.position + size;
            if (elementEnd > cuesEnd) {
                break;
            }
            if (id == ID_CUE_POINT) {
                long clusterPos = readEarliestClusterPositionInCuePoint(cursor, elementEnd);
                if (clusterPos >= 0L && clusterPos < earliest) {
                    earliest = clusterPos;
                }
            }
            cursor.position = (int) elementEnd;
        }
        return earliest == Long.MAX_VALUE ? -1L : earliest;
    }

    private static long readEarliestClusterPositionInCuePoint(@NonNull EbmlCursor cursor, long cuePointEnd) {
        long earliest = Long.MAX_VALUE;
        while (cursor.position < cuePointEnd) {
            int id = cursor.readElementId();
            if (id < 0) {
                break;
            }
            long size = cursor.readElementSize();
            if (size < 0L) {
                break;
            }
            long elementEnd = cursor.position + size;
            if (elementEnd > cuePointEnd) {
                break;
            }
            if (id == ID_CUE_CLUSTER_POSITION) {
                long value = cursor.readUnsignedVint((int) size);
                if (value >= 0L && value < earliest) {
                    earliest = value;
                }
            }
            cursor.position = (int) elementEnd;
        }
        return earliest == Long.MAX_VALUE ? -1L : earliest;
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull File file) throws IOException {
        long length = file.length();
        if (length <= 0L || length > 64L * 1024L * 1024L) {
            byte[] buffer = new byte[(int) Math.min(length, 64L * 1024L * 1024L)];
            try (FileInputStream input = new FileInputStream(file)) {
                int offset = 0;
                int read;
                while (offset < buffer.length && (read = input.read(buffer, offset, buffer.length - offset)) != -1) {
                    offset += read;
                }
                return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
            }
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read == -1) {
                    return Arrays.copyOf(data, offset);
                }
                offset += read;
            }
        }
        return data;
    }

    private static int indexOf(@NonNull byte[] data, @NonNull byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final class EbmlCursor {
        @NonNull
        final byte[] data;
        int position;

        EbmlCursor(@NonNull byte[] data) {
            this.data = data;
        }

        int readElementId() {
            if (position >= data.length) {
                return -1;
            }
            int first = data[position] & 0xFF;
            int length = vintLength(first);
            if (position + length > data.length) {
                return -1;
            }
            int id = first & ((1 << (8 - length)) - 1);
            for (int i = 1; i < length; i++) {
                id = (id << 8) | (data[position + i] & 0xFF);
            }
            position += length;
            return id;
        }

        long readElementSize() {
            if (position >= data.length) {
                return -1L;
            }
            int first = data[position] & 0xFF;
            int length = vintLength(first);
            if (position + length > data.length) {
                return -1L;
            }
            long size = first & ((1 << (8 - length)) - 1);
            for (int i = 1; i < length; i++) {
                size = (size << 8) | (data[position + i] & 0xFF);
            }
            position += length;
            return size;
        }

        private static int vintLength(int firstByte) {
            int length = 1;
            int mask = 0x80;
            while (length < 8 && (firstByte & mask) == 0) {
                mask >>= 1;
                length++;
            }
            return length;
        }

        long readUnsignedVint(int size) {
            if (size <= 0 || position + size > data.length) {
                return -1L;
            }
            long value = data[position] & 0xFF;
            int mask = (1 << (8 - size)) - 1;
            value &= mask;
            for (int i = 1; i < size; i++) {
                value = (value << 8) | (data[position + i] & 0xFF);
            }
            position += size;
            return value;
        }
    }
}
