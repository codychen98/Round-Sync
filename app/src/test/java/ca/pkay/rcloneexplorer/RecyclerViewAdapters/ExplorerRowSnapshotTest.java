package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExplorerRowSnapshotTest {

    @Test
    public void hasSameIdentity_trueWhenOnlyRowMetadataChanges() {
        ExplorerRowSnapshot cachedFolderRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                true,
                "",
                0L,
                100L);
        ExplorerRowSnapshot refreshedFileRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                200L);

        assertTrue(cachedFolderRow.hasSameIdentity(refreshedFileRow));
    }

    @Test
    public void hasSameDisplayContent_falseWhenDirectoryFlagChanges() {
        ExplorerRowSnapshot cachedFolderRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                true,
                "",
                0L,
                100L);
        ExplorerRowSnapshot refreshedFileRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "",
                0L,
                100L);

        assertFalse(cachedFolderRow.hasSameDisplayContent(refreshedFileRow));
    }

    @Test
    public void hasSameDisplayContent_falseWhenMimeTypeChanges() {
        ExplorerRowSnapshot staleMimeRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "application/octet-stream",
                1024L,
                100L);
        ExplorerRowSnapshot refreshedMimeRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                100L);

        assertFalse(staleMimeRow.hasSameDisplayContent(refreshedMimeRow));
    }

    @Test
    public void hasSameDisplayContent_falseWhenSizeOrModTimeChanges() {
        ExplorerRowSnapshot baselineRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                100L);
        ExplorerRowSnapshot sizeChangedRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                2048L,
                100L);
        ExplorerRowSnapshot modTimeChangedRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                200L);

        assertFalse(baselineRow.hasSameDisplayContent(sizeChangedRow));
        assertFalse(baselineRow.hasSameDisplayContent(modTimeChangedRow));
    }

    @Test
    public void hasSameDisplayContent_trueWhenRowMetadataMatches() {
        ExplorerRowSnapshot originalRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                100L);
        ExplorerRowSnapshot matchingRow = new ExplorerRowSnapshot(
                "drive",
                "/library/video.mp4",
                "video.mp4",
                false,
                "video/mp4",
                1024L,
                100L);

        assertTrue(originalRow.hasSameDisplayContent(matchingRow));
    }
}
