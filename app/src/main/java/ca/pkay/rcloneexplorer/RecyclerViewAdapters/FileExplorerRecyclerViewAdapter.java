package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ca.pkay.rcloneexplorer.Glide.RetryRequestListener;
import ca.pkay.rcloneexplorer.Glide.HttpServeThumbnailGlideUrl;
import ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.MediaFolderPolicy;
import ca.pkay.rcloneexplorer.util.PolicyType;
import ca.pkay.rcloneexplorer.util.SyncLog;
import io.github.x0b.safdav.SafAccessProvider;
import io.github.x0b.safdav.file.FileAccessError;

public class FileExplorerRecyclerViewAdapter extends RecyclerView.Adapter<FileExplorerRecyclerViewAdapter.ViewHolder> {

    private static final String TAG = "FileExplorerRVA";
    public static final int VIEW_MODE_LIST = 0;
    public static final int VIEW_MODE_GRID = 1;

    private List<FileItem> files;
    private View emptyView;
    private View noSearchResultsView;
    private OnClickListener listener;
    private boolean isInSelectMode;
    private List<FileItem> selectedItems;
    private boolean isInMoveMode;
    private boolean isInSearchMode;
    private boolean canSelect;
    private boolean showThumbnails;
    private boolean serverReady = false;
    /** When false, policy-extended thumbnail retries are not scheduled (fragment paused). */
    private volatile boolean thumbnailHostResumed = true;
    private boolean optionsDisabled;
    private boolean wrapFileNames;
    private Context context;
    private long sizeLimit;
    private int viewMode = VIEW_MODE_LIST;
    private ThumbnailProgressListener progressListener;
    @Nullable
    private MultiFolderPickerDelegate multiFolderPickerDelegate;
    /** When {@link #multiFolderPickerDelegate} is set, directory checkboxes only show in this mode. */
    private boolean folderPolicyPickMode;
    private int thumbnailTotal = 0;
    private final AtomicInteger thumbnailLoaded = new AtomicInteger(0);
    /** Incremented when thumbnail progress accounting resets (new folder / resort). */
    private final AtomicLong thumbnailDataGeneration = new AtomicLong(0L);
    /** Cached per-remote thumbnail allow-list; invalidated when list or settings may have changed. */
    private String thumbPolicyCachedRemoteName;
    private Set<String> thumbPolicyCachedAllowed = Collections.emptySet();

    private static final ConcurrentHashMap<String, Long> THUMB_PIPELINE_LOG_AT = new ConcurrentHashMap<>();
    private static final long THUMB_PIPELINE_LOG_COOLDOWN_MS = 60_000L;

    public interface OnClickListener {
        void onFileClicked(FileItem fileItem);
        void onDirectoryClicked(FileItem fileItem, int position);
        void onFilesSelected();
        void onFileDeselected();
        void onFileOptionsClicked(View view, FileItem fileItem);
        String[] getThumbnailServerParams();

        /** Incremented when serve URL identity changes; used to drop stale Glide retries. */
        default int getThumbnailUrlEpoch() {
            return 0;
        }
    }

    public interface ThumbnailProgressListener {
        void onThumbnailProgress(int loaded, int total);
        void onThumbnailLoadingComplete();
    }

    /** Optional directory checkboxes for media-folder policy multi-select picker. */
    public interface MultiFolderPickerDelegate {
        boolean isPathSelected(@NonNull String itemPath);

        void onDirectorySelectionClicked(@NonNull FileItem directory, int adapterPosition);

        /** Long-press on a directory in navigate mode; return true if handled (e.g. enter pick mode). */
        default boolean onDirectoryLongPressToEnterPickMode(@NonNull FileItem directory, int adapterPosition) {
            return false;
        }
    }

    public FileExplorerRecyclerViewAdapter(Context context, View emptyView, View noSearchResultsView, OnClickListener listener) {
        files = new ArrayList<>();
        this.context = context;
        this.emptyView = emptyView;
        this.noSearchResultsView = noSearchResultsView;
        this.listener = listener;
        isInSelectMode = false;
        selectedItems = new ArrayList<>();
        isInMoveMode = false;
        isInSearchMode = false;
        canSelect = true;
        wrapFileNames = true;
        optionsDisabled = false;
        sizeLimit = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(context.getString(R.string.pref_key_thumbnail_size_limit),
                        context.getResources().getInteger(R.integer.default_thumbnail_size_limit));
    }

    @Override
    public int getItemViewType(int position) {
        return viewMode;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes = (viewType == VIEW_MODE_GRID)
                ? R.layout.fragment_file_explorer_grid_item
                : R.layout.fragment_file_explorer_item;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        final FileItem item = files.get(position);

        holder.fileItem = item;

        if (holder.gridThumbnailContainer != null) {
            holder.gridThumbnailContainer.post(() -> {
                int width = holder.gridThumbnailContainer.getWidth();
                if (width > 0) {
                    ViewGroup.LayoutParams lp = holder.gridThumbnailContainer.getLayoutParams();
                    if (lp.height != width) {
                        lp.height = width;
                        holder.gridThumbnailContainer.setLayoutParams(lp);
                    }
                }
            });
        }

        if (item.isDir()) {
            holder.dirIcon.setVisibility(View.VISIBLE);
            holder.fileIcon.setVisibility(View.GONE);
            if (holder.fileSize != null) holder.fileSize.setVisibility(View.GONE);
            if (holder.interpunct != null) holder.interpunct.setVisibility(View.GONE);
        } else {
            holder.fileIcon.setVisibility(View.VISIBLE);
            holder.dirIcon.setVisibility(View.GONE);
            if (holder.fileSize != null) {
                holder.fileSize.setText(item.getHumanReadableSize());
                holder.fileSize.setVisibility(View.VISIBLE);
            }
            if (holder.interpunct != null) holder.interpunct.setVisibility(View.VISIBLE);
        }

        if (showThumbnails && !item.isDir()) {
            boolean localLoad = item.getRemote().getType() == RemoteItem.SAFW;
            String mimeType = item.getMimeType();

            if (mimeType.startsWith("image/") && item.getSize() <= sizeLimit) {
                holder.fileIcon.setImageTintList(null);
                RequestOptions glideOption = new RequestOptions()
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .placeholder(R.drawable.ic_file)
                        .error(R.drawable.ic_file);
                if (localLoad) {
                    bindSafFile(holder, item, glideOption);
                } else if (serverReady) {
                    if (!isHttpThumbnailPolicyAllowedForNetworkThumbnail(item)) {
                        maybeLogThumbPipelineDbg(item, "imagePolicySkip", false);
                        cancelActiveRetryListener(holder);
                        Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                        holder.fileIcon.setImageTintList(holder.defaultIconTint);
                        holder.fileIcon.setImageResource(R.drawable.ic_file);
                    } else {
                        maybeLogThumbPipelineDbg(item, "imageGlideIssued", true);
                        boolean extendedThumbRetry = isExtendedThumbnailRetryPolicy(item);
                        RetryRequestListener.ThumbnailExtendedRetryScheduleGate extendedGate =
                                extendedThumbRetry
                                        ? () -> thumbnailHostResumed && serverReady
                                        : null;
                        RetryRequestListener retryListener = new ProgressTrackingRetryListener(
                                ThumbnailServerManager.getInstance(),
                                rl -> Glide.with(context.getApplicationContext())
                                        .load(new HttpServeThumbnailGlideUrl(buildThumbnailUrl(item)))
                                        .apply(glideOption)
                                        .thumbnail(0.1f)
                                        .listener(rl)
                                        .into(holder.fileIcon),
                                context.getApplicationContext(),
                                item.getPath() + "|img",
                                () -> listener.getThumbnailUrlEpoch(),
                                extendedThumbRetry,
                                extendedGate);
                        cancelActiveRetryListener(holder);
                        holder.activeRetryListener = retryListener;
                        Glide.with(context.getApplicationContext())
                                .load(new HttpServeThumbnailGlideUrl(buildThumbnailUrl(item)))
                                .apply(glideOption)
                                .thumbnail(0.1f)
                                .listener(retryListener)
                                .into(holder.fileIcon);
                    }
                } else {
                    maybeLogThumbPipelineDbg(item, "imagePlaceholderNotReady",
                            isHttpThumbnailPolicyAllowedForNetworkThumbnail(item));
                    cancelActiveRetryListener(holder);
                    Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                    holder.fileIcon.setImageTintList(holder.defaultIconTint);
                    holder.fileIcon.setImageResource(R.drawable.ic_file);
                }
            } else if (mimeType.startsWith("video/") && !localLoad) {
                holder.fileIcon.setImageTintList(null);
                RequestOptions glideOption = new RequestOptions()
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .placeholder(R.drawable.ic_file)
                        .error(R.drawable.ic_file);
                if (serverReady) {
                    if (!isHttpThumbnailPolicyAllowedForNetworkThumbnail(item)) {
                        maybeLogThumbPipelineDbg(item, "videoPolicySkip", false);
                        cancelActiveRetryListener(holder);
                        Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                        holder.fileIcon.setImageTintList(holder.defaultIconTint);
                        holder.fileIcon.setImageResource(R.drawable.ic_file);
                    } else {
                        maybeLogThumbPipelineDbg(item, "videoGlideIssued", true);
                        boolean extendedThumbRetry = isExtendedThumbnailRetryPolicy(item);
                        RetryRequestListener.ThumbnailExtendedRetryScheduleGate extendedGate =
                                extendedThumbRetry
                                        ? () -> thumbnailHostResumed && serverReady
                                        : null;
                        RetryRequestListener retryListener = new ProgressTrackingRetryListener(
                                ThumbnailServerManager.getInstance(),
                                rl -> Glide.with(context.getApplicationContext())
                                        .load(new VideoThumbnailUrl(buildThumbnailUrl(item)))
                                        .apply(glideOption)
                                        .listener(rl)
                                        .into(holder.fileIcon),
                                context.getApplicationContext(),
                                item.getPath() + "|vid",
                                () -> listener.getThumbnailUrlEpoch(),
                                extendedThumbRetry,
                                extendedGate);
                        cancelActiveRetryListener(holder);
                        holder.activeRetryListener = retryListener;
                        Glide.with(context.getApplicationContext())
                                .load(new VideoThumbnailUrl(buildThumbnailUrl(item)))
                                .apply(glideOption)
                                .listener(retryListener)
                                .into(holder.fileIcon);
                    }
                } else {
                    maybeLogThumbPipelineDbg(item, "videoPlaceholderNotReady",
                            isHttpThumbnailPolicyAllowedForNetworkThumbnail(item));
                    cancelActiveRetryListener(holder);
                    Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                    holder.fileIcon.setImageTintList(holder.defaultIconTint);
                    holder.fileIcon.setImageResource(R.drawable.ic_file);
                }
            } else {
                Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                holder.fileIcon.setImageTintList(holder.defaultIconTint);
                holder.fileIcon.setImageResource(R.drawable.ic_file);
            }
        }

        if (holder.fileModTime != null) {
            RemoteItem itemRemote = item.getRemote();
            if (!itemRemote.isDirectoryModifiedTimeSupported() && item.isDir()) {
                holder.fileModTime.setVisibility(View.GONE);
            } else {
                holder.fileModTime.setVisibility(View.VISIBLE);
                holder.fileModTime.setText(item.getHumanReadableModTime());
            }
        }
        
        holder.fileName.setText(item.getName());

        if (isInSelectMode) {
            if (selectedItems.contains(item)) {
                holder.view.setBackgroundColor(getSelectionBackgroundColor());
            } else {
                holder.view.setBackgroundColor(Color.TRANSPARENT);
            }
        } else {
            holder.view.setBackgroundColor(Color.TRANSPARENT);
        }

        if (isInMoveMode) {
            if (item.isDir()) {
                holder.view.setAlpha(1f);
            } else {
                holder.view.setAlpha(.5f);
            }
        } else if (holder.view.getAlpha() == .5f) {
            holder.view.setAlpha(1f);
        }

        if ((isInSelectMode || isInMoveMode) && !optionsDisabled) {
            holder.fileOptions.setVisibility(View.INVISIBLE);
        } else if (optionsDisabled) {
            holder.fileOptions.setVisibility(View.GONE);
        } else {
            holder.fileOptions.setVisibility(View.VISIBLE);
            holder.fileOptions.setOnClickListener(v -> listener.onFileOptionsClicked(v, item));
        }

        if (wrapFileNames) {
            holder.fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            holder.fileName.setSingleLine(true);
        } else {
            holder.fileName.setEllipsize(null);
            holder.fileName.setSingleLine(false);
        }

        if (holder.folderPolicyPickCheckbox != null) {
            if (multiFolderPickerDelegate != null && folderPolicyPickMode && item.isDir()) {
                holder.folderPolicyPickCheckbox.setVisibility(View.VISIBLE);
                holder.folderPolicyPickCheckbox.setChecked(
                        multiFolderPickerDelegate.isPathSelected(item.getPath()));
                holder.folderPolicyPickCheckbox.setOnClickListener(v -> {
                    int pos = holder.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION || multiFolderPickerDelegate == null) {
                        return;
                    }
                    multiFolderPickerDelegate.onDirectorySelectionClicked(item, pos);
                    boolean selectedNow = multiFolderPickerDelegate.isPathSelected(item.getPath());
                    ((CheckBox) v).setChecked(selectedNow);
                });
            } else {
                holder.folderPolicyPickCheckbox.setVisibility(View.GONE);
                holder.folderPolicyPickCheckbox.setOnClickListener(null);
            }
        }

        holder.view.setOnClickListener(view -> {
            if (isInSelectMode) {
                onLongClickAction(item, holder);
            } else {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return;
                }
                onClickAction(item, pos);
            }
        });

        holder.view.setOnLongClickListener(view -> {
            if (item.isDir() && multiFolderPickerDelegate != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return true;
                }
                if (!folderPolicyPickMode) {
                    if (multiFolderPickerDelegate.onDirectoryLongPressToEnterPickMode(item, pos)) {
                        return true;
                    }
                } else {
                    multiFolderPickerDelegate.onDirectorySelectionClicked(item, pos);
                    notifyItemChanged(pos);
                    return true;
                }
            }
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder);
            }
            return true;
        });

        if (holder.icons != null) {
            holder.icons.setOnClickListener(v -> {
                if (multiFolderPickerDelegate != null && item.isDir()) {
                    return;
                }
                if (!isInMoveMode && canSelect) {
                    onLongClickAction(item, holder);
                }
            });
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        cancelActiveRetryListener(holder);
    }

    private void cancelActiveRetryListener(ViewHolder holder) {
        if (holder.activeRetryListener != null) {
            holder.activeRetryListener.cancel();
            holder.activeRetryListener = null;
        }
    }

    private void invalidateThumbPolicyCache() {
        thumbPolicyCachedRemoteName = null;
        thumbPolicyCachedAllowed = Collections.emptySet();
    }

    private Set<String> getThumbAllowedFolders(@NonNull String remoteName) {
        if (remoteName.equals(thumbPolicyCachedRemoteName)) {
            return thumbPolicyCachedAllowed;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> allowed = MediaFolderPolicy.INSTANCE.readAllowedFolders(prefs, remoteName, PolicyType.THUMBNAIL);
        thumbPolicyCachedRemoteName = remoteName;
        thumbPolicyCachedAllowed = allowed;
        return allowed;
    }

    /**
     * When false, skip HTTP thumbnail loads (no request, no new Glide disk cache) for this file.
     * SAFW and other bypass remotes always return true.
     */
    private boolean isHttpThumbnailPolicyAllowedForNetworkThumbnail(@NonNull FileItem item) {
        RemoteItem remote = item.getRemote();
        if (remote == null) {
            return true;
        }
        if (remote.getType() == RemoteItem.SAFW) {
            return true;
        }
        if (!MediaFolderPolicy.INSTANCE.shouldApplyAllowListGating(remote)) {
            return true;
        }
        Set<String> allowed = getThumbAllowedFolders(remote.getName());
        String pathForPolicy = MediaFolderPolicy.INSTANCE.explorerPathForPolicyCheck(
                remote.getName(),
                item.getPath());
        return MediaFolderPolicy.INSTANCE.isPathAllowed(remote, remote.getName(), pathForPolicy, allowed);
    }

    /**
     * Media-folder policy allow-list applies to this remote and the file path is allowed for
     * thumbnails — these rows use extended Glide retries while resumed and the server is ready.
     */
    private boolean isExtendedThumbnailRetryPolicy(@NonNull FileItem item) {
        RemoteItem remote = item.getRemote();
        if (remote == null || remote.getType() == RemoteItem.SAFW) {
            return false;
        }
        if (!MediaFolderPolicy.INSTANCE.shouldApplyAllowListGating(remote)) {
            return false;
        }
        return isHttpThumbnailPolicyAllowedForNetworkThumbnail(item);
    }

    /**
     * Throttled per file path + event so opening a large folder does not flood {@code sync.log}.
     * Filter exports with title {@code VidThumbDbg}.
     */
    private void maybeLogThumbPipelineDbg(
            @NonNull FileItem item,
            @NonNull String event,
            boolean policyThumbAllowed) {
        if (context == null) {
            return;
        }
        String key = item.getPath() + "|" + event;
        long now = System.currentTimeMillis();
        Long last = THUMB_PIPELINE_LOG_AT.get(key);
        if (last != null && now - last < THUMB_PIPELINE_LOG_COOLDOWN_MS) {
            return;
        }
        if (THUMB_PIPELINE_LOG_AT.size() > 800) {
            THUMB_PIPELINE_LOG_AT.clear();
        }
        THUMB_PIPELINE_LOG_AT.put(key, now);
        ThumbnailServerManager mgr = ThumbnailServerManager.getInstance();
        SyncLog.info(context, "VidThumbDbg",
                "event=" + event
                        + " path=" + item.getPath()
                        + " name=" + item.getName()
                        + " serverReady=" + serverReady
                        + " policyThumbAllowed=" + policyThumbAllowed
                        + " mgrState=" + mgr.getSyncState()
                        + " serveGen=" + mgr.getServeGeneration()
                        + " adapterGen=" + thumbnailDataGeneration.get());
    }

    private String buildThumbnailUrl(FileItem item) {
        String[] serverParams = listener.getThumbnailServerParams();
        String hiddenPath = serverParams[0];
        int serverPort = Integer.parseInt(serverParams[1]);
        Uri.Builder builder = Uri.parse("http://127.0.0.1:" + serverPort)
                .buildUpon()
                .appendEncodedPath(hiddenPath);
        for (String seg : item.getPath().split("/")) {
            if (!seg.isEmpty()) {
                builder.appendPath(seg);
            }
        }
        return builder.build().toString();
    }

    private void bindSafFile(@NonNull ViewHolder holder, FileItem item, RequestOptions glideOption) {
        try {
            Uri contentUri = SafAccessProvider.getDirectServer(context).getDocumentUri('/'+ item.getPath());
            Glide
                    .with(context.getApplicationContext())
                    .load(contentUri)
                    .apply(glideOption)
                    .thumbnail(0.1f)
                    .into(holder.fileIcon);
        } catch (FileAccessError e) {
            FLog.e(TAG, "onBindViewHolder: SAF error", e);
            holder.fileIcon.setImageTintList(holder.defaultIconTint);
            holder.fileIcon.setImageResource(R.drawable.ic_file);
        }
    }

    @Override
    public int getItemCount() {
        if (files == null) {
            return 0;
        } else {
            return files.size();
        }
    }

    public void disableFileOptions() {
        optionsDisabled = true;
    }

    public void showThumbnails(boolean showThumbnails) {
        this.showThumbnails = showThumbnails;
    }

    public void setThumbnailServerReady(boolean ready) {
        this.serverReady = ready;
    }

    /**
     * Called from hosting {@code Fragment} {@code onResume} / {@code onPause} so policy-extended
     * Glide retries do not run while the fragment is not resumed.
     */
    public void setThumbnailHostResumed(boolean resumed) {
        thumbnailHostResumed = resumed;
    }

    /**
     * Cancels scheduled Glide retries and clears in-flight thumbnail loads for visible file rows
     * before the thumbnail serve lease is released (fragment {@code onStop}). Idempotent.
     * Only runs when {@link #showThumbnails} is true; directory rows are skipped.
     */
    public void clearVisibleThumbnailGlideRequestsOnStop(@NonNull RecyclerView recyclerView) {
        if (!showThumbnails || context == null) {
            return;
        }
        int childCount = recyclerView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder rawHolder = recyclerView.getChildViewHolder(child);
            if (!(rawHolder instanceof ViewHolder)) {
                continue;
            }
            ViewHolder holder = (ViewHolder) rawHolder;
            if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) {
                continue;
            }
            FileItem item = holder.fileItem;
            if (item == null || item.isDir()) {
                continue;
            }
            cancelActiveRetryListener(holder);
            Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
            holder.fileIcon.setImageTintList(holder.defaultIconTint);
            holder.fileIcon.setImageResource(R.drawable.ic_file);
        }
    }

    public List<FileItem> getCurrentContent() {
        return new ArrayList<>(files);
    }

    public void clear() {
        if (files == null) {
            return;
        }
        int count = files.size();
        files.clear();
        isInSelectMode = false;
        if (!selectedItems.isEmpty()) {
            selectedItems.clear();
            listener.onFileDeselected();
        }
        notifyItemRangeRemoved(0, count);
    }

    public void newData(List<FileItem> data) {
        invalidateThumbPolicyCache();
        thumbnailTotal = countThumbnailTargets(data);
        thumbnailLoaded.set(0);
        long gen = thumbnailDataGeneration.incrementAndGet();
        if (context != null) {
            SyncLog.info(context, "MediaPrepDbg", "event=adapterNewData gen=" + gen + " loaded=0 total="
                    + thumbnailTotal + " listSize=" + data.size());
        }
        this.clear();
        files = new ArrayList<>(data);
        isInSelectMode = false;
        if (files.isEmpty()) {
            showEmptyState(true);
        } else {
            showEmptyState(false);
        }
        if (isInMoveMode) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeInserted(0, files.size());
        }
        notifyThumbnailIdleIfNoTargets();
    }

    public void updateData(List<FileItem> data) {
        invalidateThumbPolicyCache();
        if (data.isEmpty()) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
            showEmptyState(true);
            thumbnailTotal = 0;
            thumbnailLoaded.set(0);
            notifyThumbnailIdleIfNoTargets();
            return;
        }
        showEmptyState(false);
        List<FileItem> newData = new ArrayList<>(data);
        List<FileItem> diff = new ArrayList<>(files);

        diff.removeAll(newData);
        for (FileItem fileItem : diff) {
            int index = files.indexOf(fileItem);
            files.remove(index);
            if (selectedItems.contains(fileItem)) {
                selectedItems.remove(fileItem);
                isInSelectMode = !selectedItems.isEmpty();
                listener.onFileDeselected();
            }
            notifyItemRemoved(index);
        }

        diff = new ArrayList<>(data);
        diff.removeAll(files);
        for (FileItem fileItem : diff) {
            int index = newData.indexOf(fileItem);
            files.add(index, fileItem);
            notifyItemInserted(index);
        }
        thumbnailTotal = countThumbnailTargets(files);
        thumbnailLoaded.set(0);
        notifyThumbnailIdleIfNoTargets();
    }

    public void updateSortedData(List<FileItem> data) {
        invalidateThumbPolicyCache();
        if (files != null) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
        }
        files = new ArrayList<>(data);
        if (files.isEmpty()) {
            showEmptyState(true);
        } else {
            showEmptyState(false);
        }
        if (isInMoveMode) {
            notifyDataSetChanged();
        } else {
            notifyItemRangeInserted(0, files.size());
        }
        thumbnailTotal = countThumbnailTargets(files);
        thumbnailLoaded.set(0);
        long gen = thumbnailDataGeneration.incrementAndGet();
        if (context != null) {
            SyncLog.info(context, "MediaPrepDbg", "event=adapterUpdateSortedData gen=" + gen + " loaded=0 total="
                    + thumbnailTotal + " listSize=" + files.size());
        }
        notifyThumbnailIdleIfNoTargets();
    }

    public void refreshData() {
        invalidateThumbPolicyCache();
        notifyDataSetChanged();
    }

    public void setMoveMode(Boolean mode) {
        isInMoveMode = mode;
    }

    public void setSearchMode(Boolean mode) {
        isInSearchMode = mode;
    }

    public void setSelectedItems(List<FileItem> selectedItems) {
        this.selectedItems = new ArrayList<>(selectedItems);
        this.isInSelectMode = true;
        notifyDataSetChanged();
    }

    public Boolean isInSelectMode() {
        return isInSelectMode;
    }

    public List<FileItem> getSelectedItems() {
        return new ArrayList<>(selectedItems);
    }

    public int getNumberOfSelectedItems() {
        return selectedItems.size();
    }

    public Boolean isInMoveMode() {
        return isInMoveMode;
    }

    public void setViewMode(int viewMode) {
        this.viewMode = viewMode;
        notifyDataSetChanged();
    }

    public int getViewMode() {
        return viewMode;
    }

    public void setWrapFileNames(boolean wrapFileNames) {
        this.wrapFileNames = wrapFileNames;
        refreshData();
    }

    public void setThumbnailProgressListener(ThumbnailProgressListener listener) {
        this.progressListener = listener;
    }

    public void setMultiFolderPickerDelegate(@Nullable MultiFolderPickerDelegate delegate) {
        this.multiFolderPickerDelegate = delegate;
        if (delegate == null) {
            this.folderPolicyPickMode = false;
        }
    }

    public void setFolderPolicyPickMode(boolean folderPolicyPickMode) {
        if (this.folderPolicyPickMode == folderPolicyPickMode) {
            return;
        }
        this.folderPolicyPickMode = folderPolicyPickMode;
        notifyDataSetChanged();
    }

    private void showEmptyState(Boolean show) {
        if (isInSearchMode) {
            if (show) {
                noSearchResultsView.setVisibility(View.VISIBLE);
            } else {
                noSearchResultsView.setVisibility(View.INVISIBLE);
            }
        } else {
            if (show) {
                emptyView.setVisibility(View.VISIBLE);
            } else {
                emptyView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void onClickAction(FileItem item, int position) {
        if (item.isDir() && multiFolderPickerDelegate != null && folderPolicyPickMode) {
            if (position != RecyclerView.NO_POSITION) {
                multiFolderPickerDelegate.onDirectorySelectionClicked(item, position);
            }
            return;
        }
        if (item.isDir() && null != listener) {
            listener.onDirectoryClicked(item, position);
        } else if (!item.isDir() && !isInMoveMode && null != listener) {
            listener.onFileClicked(item);
        }
    }

    public void toggleSelectAll() {
        if (null == files) {
            return;
        }
        if (selectedItems.size() == files.size()) {
            isInSelectMode = false;
            selectedItems.clear();
            listener.onFileDeselected();
        } else {
            isInSelectMode = true;
            selectedItems.clear();
            selectedItems.addAll(files);
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    public void cancelSelection() {
        isInSelectMode = false;
        selectedItems.clear();
        listener.onFileDeselected();
        notifyDataSetChanged();
    }

    public void setCanSelect(Boolean canSelect) {
        this.canSelect = canSelect;
    }

    private int getSelectionBackgroundColor() {
        return context.getColor(R.color.selectedItem);
    }

    private void onLongClickAction(FileItem item, ViewHolder holder) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
            holder.view.setBackgroundColor(Color.TRANSPARENT);
            if (selectedItems.size() == 0) {
                isInSelectMode = false;
                listener.onFileDeselected();
            }
            listener.onFileDeselected();
        } else {
            selectedItems.add(item);
            isInSelectMode = true;
            holder.view.setBackgroundColor(getSelectionBackgroundColor());
            listener.onFilesSelected();
        }
        notifyDataSetChanged();
    }

    private int countThumbnailTargets(@NonNull List<FileItem> items) {
        int total = 0;
        if (!showThumbnails) {
            return 0;
        }
        for (FileItem item : items) {
            if (item.isDir()) {
                continue;
            }
            if (item.getRemote() == null) {
                continue;
            }
            boolean localLoad = item.getRemote().getType() == RemoteItem.SAFW;
            if (localLoad) {
                continue;
            }
            String mime = item.getMimeType();
            if ((mime.startsWith("image/") && item.getSize() <= sizeLimit)
                    || mime.startsWith("video/")) {
                if (isHttpThumbnailPolicyAllowedForNetworkThumbnail(item)) {
                    total++;
                }
            }
        }
        return total;
    }

    private void notifyThumbnailIdleIfNoTargets() {
        if (thumbnailTotal == 0 && progressListener != null) {
            progressListener.onThumbnailLoadingComplete();
        }
    }

    private class ProgressTrackingRetryListener extends RetryRequestListener {

        ProgressTrackingRetryListener(
                @NonNull ThumbnailServerManager serverManager,
                @NonNull RetryRequestListener.RetryLoadCallback loadCallback,
                @NonNull Context logContext,
                @NonNull String debugLoadKey,
                @NonNull RetryRequestListener.ThumbnailRetryEpochSource epochSource,
                boolean policyExtendedRetries,
                @Nullable RetryRequestListener.ThumbnailExtendedRetryScheduleGate extendedScheduleGate) {
            super(
                    serverManager,
                    loadCallback,
                    logContext,
                    debugLoadKey,
                    epochSource,
                    policyExtendedRetries,
                    extendedScheduleGate);
        }

        @Override
        public boolean onResourceReady(
                @NonNull android.graphics.drawable.Drawable resource,
                @NonNull Object model,
                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                @NonNull com.bumptech.glide.load.DataSource dataSource,
                boolean isFirstResource) {
            boolean result = super.onResourceReady(resource, model, target, dataSource, isFirstResource);
            notifyProgress();
            return result;
        }

        @Override
        public boolean onLoadFailed(
                @androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                Object model,
                @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                boolean isFirstResource) {
            boolean retrying = super.onLoadFailed(e, model, target, isFirstResource);
            if (!retrying) {
                notifyProgress();
            }
            return retrying;
        }

        private void notifyProgress() {
            if (progressListener == null || thumbnailTotal == 0) return;
            int loaded = thumbnailLoaded.incrementAndGet();
            if (context != null) {
                SyncLog.info(context, "MediaPrepDbg", "event=adapterNotifyProgress gen="
                        + thumbnailDataGeneration.get() + " loaded=" + loaded + " total=" + thumbnailTotal);
            }
            progressListener.onThumbnailProgress(loaded, thumbnailTotal);
            if (loaded >= thumbnailTotal) {
                progressListener.onThumbnailLoadingComplete();
            }
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        public final View view;
        public final View icons;
        public final ImageView fileIcon;
        public final ImageView dirIcon;
        public final TextView fileName;
        public final TextView fileModTime;
        public final TextView fileSize;
        public final TextView interpunct;
        public final ImageButton fileOptions;
        public final View gridThumbnailContainer;
        @Nullable
        public final CheckBox folderPolicyPickCheckbox;
        public FileItem fileItem;
        public final ColorStateList defaultIconTint;
        public RetryRequestListener activeRetryListener = null;

        ViewHolder(View itemView) {
            super(itemView);
            this.view = itemView;
            this.icons = view.findViewById(R.id.icons);
            this.fileIcon = view.findViewById(R.id.file_icon);
            this.dirIcon = view.findViewById(R.id.dir_icon);
            this.fileName = view.findViewById(R.id.file_name);
            this.fileModTime = view.findViewById(R.id.file_modtime);
            this.fileSize = view.findViewById(R.id.file_size);
            this.fileOptions = view.findViewById(R.id.file_options);
            this.interpunct = view.findViewById(R.id.interpunct);
            this.gridThumbnailContainer = view.findViewById(R.id.grid_thumbnail_container);
            this.folderPolicyPickCheckbox = view.findViewById(R.id.folder_policy_pick_checkbox);
            this.defaultIconTint = ImageViewCompat.getImageTintList(this.fileIcon);
        }
    }
}

