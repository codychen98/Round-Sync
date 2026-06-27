package ca.pkay.rcloneexplorer.Glide;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Exo {@link androidx.media3.datasource.DataSource} that serves a remote MKV from a downloaded head
 * prefix and tail suffix. Matroska demuxers need both the EBML header (start) and Cues (often near
 * end); a head-only partial file cannot prepare.
 */
final class VideoMkvSparseDataSource extends BaseDataSource {

    static final class HeadTailFiles {
        final long totalSize;
        final File headFile;
        final long headBytes;
        final File tailFile;
        final long tailRemoteStart;
        final long tailBytes;

        HeadTailFiles(
                long totalSize,
                @NonNull File headFile,
                long headBytes,
                @NonNull File tailFile,
                long tailRemoteStart,
                long tailBytes) {
            this.totalSize = totalSize;
            this.headFile = headFile;
            this.headBytes = headBytes;
            this.tailFile = tailFile;
            this.tailRemoteStart = tailRemoteStart;
            this.tailBytes = tailBytes;
        }
    }

    static final class Factory implements androidx.media3.datasource.DataSource.Factory {
        private final HeadTailFiles files;
        private final Uri uri;

        Factory(@NonNull HeadTailFiles files, @NonNull Uri uri) {
            this.files = files;
            this.uri = uri;
        }

        @NonNull
        @Override
        public androidx.media3.datasource.DataSource createDataSource() {
            return new VideoMkvSparseDataSource(files, uri);
        }
    }

    private final HeadTailFiles files;
    private final Uri uri;
    @Nullable
    private InputStream currentStream;
    private long currentStreamBase;
    private long readPosition;
    private long bytesRemaining;
    private boolean opened;

    private VideoMkvSparseDataSource(@NonNull HeadTailFiles files, @NonNull Uri uri) {
        super(/* isNetwork= */ false);
        this.files = files;
        this.uri = uri;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        readPosition = dataSpec.position;
        if (dataSpec.length == C.LENGTH_UNSET) {
            bytesRemaining = files.totalSize - dataSpec.position;
        } else {
            bytesRemaining = dataSpec.length;
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        if (readPosition >= files.totalSize) {
            return C.RESULT_END_OF_INPUT;
        }
        int toRead = (int) Math.min(length, bytesRemaining);
        int copied = 0;
        while (copied < toRead) {
            long readPos = readPosition + copied;
            int chunk = readAtPosition(readPos, buffer, offset + copied, toRead - copied);
            if (chunk == C.RESULT_END_OF_INPUT) {
                if (copied == 0) {
                    return C.RESULT_END_OF_INPUT;
                }
                break;
            }
            copied += chunk;
        }
        if (copied == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        readPosition += copied;
        bytesRemaining -= copied;
        bytesTransferred(copied);
        return copied;
    }

    private int readAtPosition(long position, @NonNull byte[] buffer, int offset, int length)
            throws IOException {
        if (position < files.headBytes) {
            return readFromFile(files.headFile, 0L, position, buffer, offset, length);
        }
        if (position >= files.tailRemoteStart && position < files.tailRemoteStart + files.tailBytes) {
            long tailOffset = position - files.tailRemoteStart;
            return readFromFile(files.tailFile, 0L, tailOffset, buffer, offset, length);
        }
        return C.RESULT_END_OF_INPUT;
    }

    private int readFromFile(
            @NonNull File file,
            long streamBase,
            long positionInFile,
            @NonNull byte[] buffer,
            int offset,
            int length) throws IOException {
        if (currentStream == null || currentStreamBase != streamBase) {
            closeCurrentStream();
            currentStream = new FileInputStream(file);
            currentStreamBase = streamBase;
        }
        long skip = positionInFile - streamBase;
        if (skip > 0) {
            long skipped = currentStream.skip(skip);
            currentStreamBase += skipped;
            if (skipped < skip) {
                return C.RESULT_END_OF_INPUT;
            }
        }
        int read = currentStream.read(buffer, offset, length);
        if (read == -1) {
            return C.RESULT_END_OF_INPUT;
        }
        currentStreamBase += read;
        return read;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return opened ? uri : null;
    }

    @Override
    public void close() throws IOException {
        closeCurrentStream();
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private void closeCurrentStream() throws IOException {
        if (currentStream != null) {
            currentStream.close();
            currentStream = null;
            currentStreamBase = 0L;
        }
    }
}
