package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Matroska Cues scan: finds the earliest {@code CueClusterPosition} so AV1 sparse Exo
 * can fetch cluster byte ranges missing from head-only prefixes.
 */
final class VideoMkvCueParser {

    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CUE_POINT = 0xBB;
    private static final int ID_CUE_CLUSTER_POSITION = 0xF1;
    private static final int ID_SEEK_HEAD = 0x114D9B74;
    private static final int ID_SEEK = 0x4DBB;
    private static final int ID_SEEK_ID = 0x53AB;
    private static final int ID_SEEK_POSITION = 0x53AC;
    private static final int ID_SEGMENT = 0x18538067;
    private static final long CUES_SLICE_BYTES = 2L * 1024L * 1024L;

    private VideoMkvCueParser() {
    }

    /**
     * @return earliest cluster byte offset in the full file, or {@code -1} when not found
     */
    static long findEarliestClusterPosition(@NonNull File tailFile) throws IOException {
        return findEarliestClusterPosition(null, tailFile);
    }

    /**
     * Uses {@code headFile} for Segment / SeekHead metadata and {@code tailFile} for Cues payload.
     */
    static long findEarliestClusterPosition(@Nullable File headFile, @NonNull File tailFile)
            throws IOException {
        long segmentStart = headFile != null ? findSegmentStartOffset(headFile) : 0L;
        long fromTail = findEarliestClusterPositionInBytes(readAllBytes(tailFile), segmentStart);
        if (fromTail >= 0L) {
            return fromTail;
        }
        if (headFile != null) {
            long fromHead = findEarliestClusterPositionInBytes(readAllBytes(headFile), segmentStart);
            if (fromHead >= 0L) {
                return fromHead;
            }
        }
        return -1L;
    }

    /**
     * Absolute file offset of the Cues element from SeekHead, or {@code -1}.
     */
    static long findCuesByteOffset(@Nullable File headFile) throws IOException {
        if (headFile == null) {
            return -1L;
        }
        byte[] head = readAllBytes(headFile);
        long segmentStart = findSegmentStartOffset(head);
        return findCuesOffsetFromSeekHead(head, segmentStart);
    }

    static long findSegmentStartOffset(@NonNull File headFile) throws IOException {
        return findSegmentStartOffset(readAllBytes(headFile));
    }

    static long findSegmentStartOffset(@NonNull byte[] head) {
        int segmentOffset = indexOf(head, new byte[]{(byte) 0x18, (byte) 0x53, (byte) 0x80, (byte) 0x67});
        return segmentOffset >= 0 ? segmentOffset : 0L;
    }

    static long findEarliestClusterPositionInCuesSlice(
            @NonNull byte[] cuesSlice,
            long segmentStart) {
        return findEarliestClusterPositionInBytes(cuesSlice, segmentStart);
    }

    @NonNull
    static List<Long> findAllClusterPositionsInCuesSlice(
            @NonNull byte[] cuesSlice,
            long segmentStart) {
        return findAllClusterPositionsInBytes(cuesSlice, segmentStart);
    }

    /**
     * All cue cluster byte offsets in the full file, sorted ascending.
     */
    @NonNull
    static List<Long> findAllClusterPositions(@Nullable File headFile, @NonNull File tailFile)
            throws IOException {
        long segmentStart = headFile != null ? findSegmentStartOffset(headFile) : 0L;
        List<Long> positions = new ArrayList<>();
        positions.addAll(findAllClusterPositionsInBytes(readAllBytes(tailFile), segmentStart));
        if (headFile != null) {
            positions.addAll(findAllClusterPositionsInBytes(readAllBytes(headFile), segmentStart));
        }
        Collections.sort(positions);
        return dedupeSorted(positions);
    }

    /**
     * Cluster positions inside {@code [headBytes, tailRemoteStart)}, up to {@code maxCount}.
     */
    @NonNull
    static List<Long> findClusterPositionsInGap(
            @Nullable File headFile,
            @NonNull File tailFile,
            long headBytes,
            long tailRemoteStart,
            int maxCount) throws IOException {
        List<Long> inGap = new ArrayList<>();
        for (long clusterPos : findAllClusterPositions(headFile, tailFile)) {
            if (clusterPos >= headBytes && clusterPos < tailRemoteStart) {
                inGap.add(clusterPos);
            }
        }
        if (inGap.size() > maxCount) {
            return inGap.subList(0, maxCount);
        }
        return inGap;
    }

    static long findEarliestClusterPositionInBytes(@NonNull byte[] data, long segmentStart) {
        List<Long> all = findAllClusterPositionsInBytes(data, segmentStart);
        return all.isEmpty() ? -1L : all.get(0);
    }

    private static long findCuesOffsetFromSeekHead(@NonNull byte[] head, long segmentStart) {
        int seekHeadOffset = indexOf(head, new byte[]{(byte) 0x11, (byte) 0x4D, (byte) 0x9B, (byte) 0x74});
        if (seekHeadOffset < 0 || seekHeadOffset + 8 > head.length) {
            return -1L;
        }
        EbmlCursor cursor = new EbmlCursor(head);
        cursor.position = seekHeadOffset + 4;
        long seekHeadSize = cursor.readElementSize();
        if (seekHeadSize <= 0L) {
            return -1L;
        }
        long seekHeadEnd = Math.min(cursor.position + seekHeadSize, head.length);
        while (cursor.position < seekHeadEnd) {
            int id = cursor.readElementId();
            if (id < 0) {
                break;
            }
            long size = cursor.readElementSize();
            if (size < 0L) {
                break;
            }
            long elementEnd = cursor.position + size;
            if (elementEnd > seekHeadEnd) {
                break;
            }
            if (id == ID_SEEK) {
                long cuesOffset = readCuesOffsetInSeek(cursor, elementEnd, segmentStart);
                if (cuesOffset >= 0L) {
                    return cuesOffset;
                }
            }
            cursor.position = (int) elementEnd;
        }
        return -1L;
    }

    private static long readCuesOffsetInSeek(@NonNull EbmlCursor cursor, long seekEnd, long segmentStart) {
        long seekPosition = -1L;
        boolean seeksCues = false;
        while (cursor.position < seekEnd) {
            int id = cursor.readElementId();
            if (id < 0) {
                break;
            }
            long size = cursor.readElementSize();
            if (size < 0L) {
                break;
            }
            long elementEnd = cursor.position + size;
            if (elementEnd > seekEnd) {
                break;
            }
            if (id == ID_SEEK_ID && size >= 4L && cursor.position + size <= elementEnd) {
                byte[] seekIdBytes = Arrays.copyOfRange(cursor.data, cursor.position, cursor.position + (int) size);
                seeksCues = bytesContainCuesId(seekIdBytes);
            } else if (id == ID_SEEK_POSITION) {
                seekPosition = cursor.readUnsignedVint((int) size);
            }
            cursor.position = (int) elementEnd;
        }
        if (!seeksCues || seekPosition < 0L) {
            return -1L;
        }
        return segmentStart + seekPosition;
    }

    @NonNull
    private static List<Long> findAllClusterPositionsInBytes(@NonNull byte[] data, long segmentStart) {
        int cuesOffset = indexOf(data, new byte[]{(byte) 0x1C, (byte) 0x53, (byte) 0xBB, (byte) 0x6B});
        if (cuesOffset < 0 || cuesOffset + 5 > data.length) {
            return Collections.emptyList();
        }
        EbmlCursor cursor = new EbmlCursor(data);
        cursor.position = cuesOffset + 4;
        long cuesSize = cursor.readElementSize();
        if (cuesSize <= 0L) {
            return Collections.emptyList();
        }
        long cuesEnd = Math.min(cursor.position + cuesSize, data.length);
        List<Long> positions = new ArrayList<>();
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
                long clusterPos = readEarliestClusterPositionInCuePoint(cursor, elementEnd, segmentStart);
                if (clusterPos >= 0L) {
                    positions.add(clusterPos);
                }
            }
            cursor.position = (int) elementEnd;
        }
        Collections.sort(positions);
        return dedupeSorted(positions);
    }

    private static boolean bytesContainCuesId(@NonNull byte[] seekIdBytes) {
        byte[] cuesId = new byte[]{(byte) 0x1C, (byte) 0x53, (byte) 0xBB, (byte) 0x6B};
        if (seekIdBytes.length < cuesId.length) {
            return false;
        }
        for (int i = 0; i <= seekIdBytes.length - cuesId.length; i++) {
            boolean match = true;
            for (int j = 0; j < cuesId.length; j++) {
                if (seekIdBytes[i + j] != cuesId[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static List<Long> dedupeSorted(@NonNull List<Long> sorted) {
        if (sorted.isEmpty()) {
            return sorted;
        }
        List<Long> out = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (long value : sorted) {
            if (value != previous) {
                out.add(value);
                previous = value;
            }
        }
        return out;
    }

    private static long readEarliestClusterPositionInCuePoint(
            @NonNull EbmlCursor cursor,
            long cuePointEnd,
            long segmentStart) {
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
                if (value >= 0L) {
                    long absolute = segmentStart + value;
                    if (absolute < earliest) {
                        earliest = absolute;
                    }
                }
            }
            cursor.position = (int) elementEnd;
        }
        return earliest == Long.MAX_VALUE ? -1L : earliest;
    }

    static long cuesSliceBytes() {
        return CUES_SLICE_BYTES;
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
            int id = 0;
            for (int i = 0; i < length; i++) {
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
