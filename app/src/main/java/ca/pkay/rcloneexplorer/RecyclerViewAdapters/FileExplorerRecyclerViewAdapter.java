package ca.pkay.rcloneexplorer.RecyclerViewAdapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.request.RequestOptions;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import ca.pkay.rcloneexplorer.Glide.RetryRequestListener;
import ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
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
    private boolean optionsDisabled;
    private boolean wrapFileNames;
    private Context context;
    private long sizeLimit;
    private int viewMode = VIEW_MODE_LIST;

    public interface OnClickListener {
        void onFileClicked(FileItem fileItem);
        void onDirectoryClicked(FileItem fileItem, int position);
        void onFilesSelected();
        void onFileDeselected();
        void onFileOptionsClicked(View view, FileItem fileItem);
        String[] getThumbnailServerParams();
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
                    String url = buildThumbnailUrl(item);
                    RetryRequestListener retryListener = new RetryRequestListener(
                            ThumbnailServerManager.getInstance(),
                            rl -> Glide.with(context.getApplicationContext())
                                    .load(new PersistentGlideUrl(url))
                                    .apply(glideOption)
                                    .thumbnail(0.1f)
                                    .listener(rl)
                                    .into(holder.fileIcon));
                    cancelActiveRetryListener(holder);
                    holder.activeRetryListener = retryListener;
                    Glide.with(context.getApplicationContext())
                            .load(new PersistentGlideUrl(url))
                            .apply(glideOption)
                            .thumbnail(0.1f)
                            .listener(retryListener)
                            .into(holder.fileIcon);
                } else {
                    cancelActiveRetryListener(holder);
                    Glide.with(context.getApplicationContext()).clear(holder.fileIcon);
                    holder.fileIcon.setImageTintList(holder.defaultIconTint);
                    holder.fileIcon.setImageResource(R.drawable.ic_file);
                }
            } else if (mimeType.startsWith("video/") && !localLoad) {
                holder.fileIcon.setImageTintList(null);
                String url = buildThumbnailUrl(item);
                RequestOptions glideOption = new RequestOptions()
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.DATA)
                        .placeholder(R.drawable.ic_file)
                        .error(R.drawable.ic_file);
                if (serverReady) {
                    RetryRequestListener retryListener = new RetryRequestListener(
                            ThumbnailServerManager.getInstance(),
                            rl -> Glide.with(context.getApplicationContext())
                                    .load(new VideoThumbnailUrl(url))
                                    .apply(glideOption)
                                    .listener(rl)
                                    .into(holder.fileIcon));
                    cancelActiveRetryListener(holder);
                    holder.activeRetryListener = retryListener;
                    Glide.with(context.getApplicationContext())
                            .load(new VideoThumbnailUrl(url))
                            .apply(glideOption)
                            .listener(retryListener)
                            .into(holder.fileIcon);
                } else {
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

        holder.view.setOnClickListener(view -> {
            if (isInSelectMode) {
                onLongClickAction(item, holder);
            } else {
                onClickAction(item, holder.getAdapterPosition());
            }
        });

        holder.view.setOnLongClickListener(view -> {
            if (!isInMoveMode && canSelect) {
                onLongClickAction(item, holder);
            }
            return true;
        });

        if (holder.icons != null) {
            holder.icons.setOnClickListener(v -> {
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

    private static class PersistentGlideUrl extends GlideUrl {

        public PersistentGlideUrl(String url) {
            super(url);
        }

        @Override
        public String getCacheKey() {
            try {
                URL url = super.toURL();
                String path = url.getPath();
                return path.substring(path.indexOf('/', 1));
            } catch (MalformedURLException e) {
                return super.getCacheKey();
            }
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
    }

    public void updateData(List<FileItem> data) {
        if (data.isEmpty()) {
            int count = files.size();
            files.clear();
            notifyItemRangeRemoved(0, count);
            showEmptyState(true);
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
    }

    public void updateSortedData(List<FileItem> data) {
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
    }

    public void refreshData() {
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
            this.defaultIconTint = ImageViewCompat.getImageTintList(this.fileIcon);
        }
    }
}

