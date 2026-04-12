package ca.pkay.rcloneexplorer.Fragments;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import ca.pkay.rcloneexplorer.Dialogs.GoToDialog;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.SortDialog;
import ca.pkay.rcloneexplorer.FileComparators;
import ca.pkay.rcloneexplorer.Items.DirectoryObject;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LargeParcel;
import ca.pkay.rcloneexplorer.util.SyncLog;
import de.felixnuesse.ui.BreadcrumbView;
import es.dmoral.toasty.Toasty;
import jp.wasabeef.recyclerview.animators.LandingAnimator;

public class RemoteFolderPickerFragment extends Fragment implements   FileExplorerRecyclerViewAdapter.OnClickListener,
                                                                            SwipeRefreshLayout.OnRefreshListener,
                                                                            BreadcrumbView.OnClickListener,
                                                                            SortDialog.OnClickListener,
                                                                            InputDialog.OnPositive,
                                                                            GoToDialog.Callbacks {


    private static final String TAG = "FileExplorerFragment";
    private static final String ARG_REMOTE = "remote_param";
    private static final String ARG_MEDIA_FOLDER_POLICY_MULTI = "media_folder_policy_multi_select";
    private static final String SAVED_MULTI_SELECTED_PATHS = "media_folder_policy_multi_paths";
    private static final String SAVED_PICK_MODE = "media_folder_policy_pick_mode";
    private static final String SHARED_PREFS_SORT_ORDER = "ca.pkay.rcexplorer.sort_order";

    private final String SAVED_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_PATH";
    private final String SAVED_CONTENT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_CONTENT";
    private final String SAVED_SEARCH_MODE = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_MODE";
    private final String SAVED_SEARCH_STRING = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_STRING";
    private final String SAVED_RENAME_ITEM = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_RENAME_ITEM";
    private final String SAVED_SELECTED_ITEMS = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SELECTED_ITEMS";
    private final String SAVED_START_AT_BOOT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_START_AT_BOOT";


    private Stack<String> pathStack;
    private Map<String, Integer> directoryPosition;
    private DirectoryObject directoryObject;

    private BreadcrumbView breadcrumbView;
    private Rclone rclone;
    private RemoteItem remote;
    private String remoteName;
    private FileExplorerRecyclerViewAdapter recyclerViewAdapter;
    private LinearLayoutManager recyclerViewLinearLayoutManager;
    private SwipeRefreshLayout swipeRefreshLayout;

    private AsyncTask fetchDirectoryTask;

    private String originalToolbarTitle;
    private int sortOrder;
    private ExtendedFloatingActionButton fab;
    private Boolean isSearchMode;
    private String searchString;
    private boolean showThumbnails;
    private boolean startAtRoot;
    private boolean goToDefaultSet;
    private Context context;
    private String thumbnailServerAuth;
    private int thumbnailServerPort;
    private int thumbnailServeLeaseId;
    private int thumbnailUrlEpoch;
    private int lastThumbnailServeGenAtReady = Integer.MIN_VALUE;
    private boolean wrapFilenames;
    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    private FolderSelectorCallback mSelectedFolderCallback;
    private String mInitialPath = "";
    private boolean mediaFolderPolicyMultiSelectMode;
    /** Navigate-only until long-press; then directory taps toggle selection without changing path. */
    private boolean mediaFolderPolicyPickMode;
    private OnBackPressedCallback mediaFolderPolicyBackCallback;
    private final LinkedHashSet<String> selectedFolderPaths = new LinkedHashSet<>();

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public RemoteFolderPickerFragment() {}

    @SuppressWarnings("unused")
    public static RemoteFolderPickerFragment newInstance(RemoteItem remoteItem, FolderSelectorCallback fsc, String initialPath) {
        return newInstance(remoteItem, fsc, initialPath, false);
    }

    public static RemoteFolderPickerFragment newInstance(RemoteItem remoteItem, FolderSelectorCallback fsc,
            String initialPath, boolean mediaFolderPolicyMultiSelect) {
        RemoteFolderPickerFragment fragment = new RemoteFolderPickerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_REMOTE, remoteItem);
        args.putBoolean(ARG_MEDIA_FOLDER_POLICY_MULTI, mediaFolderPolicyMultiSelect);
        fragment.setArguments(args);
        fragment.setSelectedFolderCallback(fsc);
        fragment.setInitialPath(initialPath);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }
        setHasOptionsMenu(true);

        remote = getArguments().getParcelable(ARG_REMOTE);
        if (remote == null) {
            return;
        }
        remoteName = remote.getName();
        mediaFolderPolicyMultiSelectMode = getArguments().getBoolean(ARG_MEDIA_FOLDER_POLICY_MULTI, false);
        pathStack = new Stack<>();
        directoryPosition = new HashMap<>();
        directoryObject = new DirectoryObject();

        String path;
        if (savedInstanceState == null) {
            path = "//" + remoteName;
            directoryObject.setPath(path);
        } else {
            path = savedInstanceState.getString(SAVED_PATH);
            if (path == null) {
                return;
            }
            directoryObject.setPath(path);
            ArrayList<FileItem> savedContent = savedInstanceState.getParcelableArrayList(SAVED_CONTENT);
            if (savedContent != null) {
                directoryObject.setContent(savedContent);
            }

            buildStackFromPath(remoteName, path);
        }

        if (mediaFolderPolicyMultiSelectMode && savedInstanceState != null) {
            mediaFolderPolicyPickMode = savedInstanceState.getBoolean(SAVED_PICK_MODE, false);
            ArrayList<String> restoredPaths = savedInstanceState.getStringArrayList(SAVED_MULTI_SELECTED_PATHS);
            if (restoredPaths != null) {
                selectedFolderPaths.clear();
                selectedFolderPaths.addAll(restoredPaths);
            }
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sortOrder = sharedPreferences.getInt(SHARED_PREFS_SORT_ORDER, SortDialog.ALPHA_ASCENDING);
        showThumbnails = sharedPreferences.getBoolean(getString(R.string.pref_key_show_thumbnails), true);
        goToDefaultSet = sharedPreferences.getBoolean(getString(R.string.pref_key_go_to_default_set), false);
        String wrapFilenamesKey = getString(R.string.pref_key_wrap_filenames);
        prefChangeListener = (pref, key) -> {
            if (key.equals(wrapFilenamesKey) && recyclerViewAdapter != null) {
                recyclerViewAdapter.setWrapFileNames(pref.getBoolean(wrapFilenamesKey, true));
            }
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener);
        wrapFilenames = sharedPreferences.getBoolean(getString(R.string.pref_key_wrap_filenames), true);

        if (goToDefaultSet) {
            startAtRoot = sharedPreferences.getBoolean(getString(R.string.pref_key_start_at_root), false);
        }

        rclone = new Rclone(getContext());

        isSearchMode = false;
        if (context != null) {
            SyncLog.info(context, "PolicyCrashDbg", "event=pickerOnCreate remote=" + remoteName
                    + " restored=" + (savedInstanceState != null));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_remote_folder_picker_list, container, false);
        if (savedInstanceState != null) {
            startAtRoot = savedInstanceState.getBoolean(SAVED_START_AT_BOOT);
        }

        if (showThumbnails) {
            initializeThumbnailParams();
        }

        swipeRefreshLayout = view.findViewById(R.id.file_explorer_srl);
        swipeRefreshLayout.setOnRefreshListener(this);

        Context context = view.getContext();

        RecyclerView recyclerView = view.findViewById(R.id.file_explorer_list);
        recyclerViewLinearLayoutManager = new LinearLayoutManager(context);
        recyclerView.setItemAnimator(new LandingAnimator());
        recyclerView.setLayoutManager(recyclerViewLinearLayoutManager);
        View emptyFolderView = view.findViewById(R.id.empty_folder_view);
        View noSearchResultsView = view.findViewById(R.id.no_search_results_view);
        recyclerViewAdapter = new FileExplorerRecyclerViewAdapter(context, emptyFolderView, noSearchResultsView, this);
        recyclerViewAdapter.showThumbnails(showThumbnails);
        recyclerViewAdapter.setWrapFileNames(wrapFilenames);
        recyclerViewAdapter.disableFileOptions();
        if (mediaFolderPolicyMultiSelectMode) {
            recyclerViewAdapter.setMultiFolderPickerDelegate(new FileExplorerRecyclerViewAdapter.MultiFolderPickerDelegate() {
                @Override
                public boolean isPathSelected(@NonNull String itemPath) {
                    return selectedFolderPaths.contains(itemPath);
                }

                @Override
                public void onDirectorySelectionClicked(@NonNull FileItem directory, int adapterPosition) {
                    String p = directory.getPath();
                    if (selectedFolderPaths.contains(p)) {
                        selectedFolderPaths.remove(p);
                    } else {
                        selectedFolderPaths.add(p);
                    }
                    if (adapterPosition != RecyclerView.NO_POSITION && recyclerViewAdapter != null) {
                        recyclerViewAdapter.notifyItemChanged(adapterPosition);
                    }
                    updateMultiSelectFabUi();
                }

                @Override
                public boolean onDirectoryLongPressToEnterPickMode(@NonNull FileItem directory, int adapterPosition) {
                    enterMediaFolderPolicyPickMode(directory);
                    return true;
                }
            });
            recyclerViewAdapter.setFolderPolicyPickMode(mediaFolderPolicyPickMode);
        }
        recyclerView.setAdapter(recyclerViewAdapter);
        observeThumbnailServerState();

        if (remote.isRemoteType(RemoteItem.SFTP) && !goToDefaultSet & savedInstanceState == null) {
            showSFTPgoToDialog();
        } else {
            if (directoryObject.isDirectoryContentEmpty()) {
                fetchDirectoryTask = new FetchDirectoryContent().execute();
                swipeRefreshLayout.setRefreshing(true);
            } else {
                recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            }
        }

        fab = view.findViewById(R.id.selectFloatingButton);


        originalToolbarTitle = ((FragmentActivity) context).getTitle().toString();
        setTitle();
        breadcrumbView = ((FragmentActivity) context).findViewById(R.id.breadcrumb_view);
        breadcrumbView.setOnClickListener(this);
        breadcrumbView.setVisibility(View.VISIBLE);
        // this will be called twice for an unknown reason. Therefore we need to clear the Crumbs.
        breadcrumbView.clearCrumbs();
        if (!mInitialPath.isEmpty()) {
            directoryObject.setPath(mInitialPath);
            breadcrumbView.buildBreadCrumbsFromPath(remote.getDisplayName()+directoryObject.getCurrentPath());
        } else {
            breadcrumbView.addCrumb(remote.getDisplayName(), "//" + remoteName);
        }

        if (savedInstanceState != null && savedInstanceState.getBoolean(SAVED_SEARCH_MODE, false)) {
            searchString = savedInstanceState.getString(SAVED_SEARCH_STRING);
            searchClicked();
        }

        configureSelectFab();

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mediaFolderPolicyBackCallback = new OnBackPressedCallback(mediaFolderPolicyMultiSelectMode) {
            @Override
            public void handleOnBackPressed() {
                handleMediaFolderPolicyMultiSelectBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mediaFolderPolicyBackCallback);
    }

    private void observeThumbnailServerState() {
        if (!showThumbnails) {
            return;
        }
        ThumbnailServerManager.getInstance().getState().observe(this, state -> {
            if (context != null) {
                SyncLog.info(context, "ThumbnailServer",
                        "Picker observer received state: " + state);
            }
            if (state == ThumbnailServerManager.ServerState.READY) {
                int serveGen = ThumbnailServerManager.getInstance().getServeGeneration();
                if (lastThumbnailServeGenAtReady != Integer.MIN_VALUE && serveGen != lastThumbnailServeGenAtReady) {
                    thumbnailUrlEpoch++;
                }
                lastThumbnailServeGenAtReady = serveGen;
                FLog.d(TAG, "Thumbnail server ready — refreshing picker items");
                if (recyclerViewAdapter != null) {
                    recyclerViewAdapter.setThumbnailServerReady(true);
                    recyclerViewAdapter.notifyDataSetChanged();
                } else if (context != null) {
                    SyncLog.error(context, "ThumbnailServer",
                            "READY received but picker recyclerViewAdapter is null");
                }
            } else if (state == ThumbnailServerManager.ServerState.STARTING) {
                if (recyclerViewAdapter != null) {
                    recyclerViewAdapter.setThumbnailServerReady(false);
                }
            } else if (state == ThumbnailServerManager.ServerState.FAILED) {
                FLog.e(TAG, "Thumbnail server failed to start (picker)");
                if (recyclerViewAdapter != null) {
                    recyclerViewAdapter.setThumbnailServerReady(false);
                }
                if (context != null) {
                    Toasty.error(context, getString(R.string.thumbnail_server_failed),
                            Toast.LENGTH_LONG, true).show();
                }
            } else if (state == ThumbnailServerManager.ServerState.STOPPED) {
                FLog.d(TAG, "Thumbnail server stopped (picker)");
                if (recyclerViewAdapter != null) {
                    recyclerViewAdapter.setThumbnailServerReady(false);
                }
            }
        });
    }

    private void enterMediaFolderPolicyPickMode(@NonNull FileItem directory) {
        if (!mediaFolderPolicyMultiSelectMode || mediaFolderPolicyPickMode) {
            return;
        }
        mediaFolderPolicyPickMode = true;
        selectedFolderPaths.add(directory.getPath());
        if (recyclerViewAdapter != null) {
            recyclerViewAdapter.setFolderPolicyPickMode(true);
        }
        updateMultiSelectFabUi();
        View v = getView();
        if (v != null && isAdded()) {
            Snackbar.make(v, getString(R.string.media_folder_policy_selection_mode), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void exitMediaFolderPolicyPickMode() {
        if (!mediaFolderPolicyPickMode) {
            return;
        }
        mediaFolderPolicyPickMode = false;
        if (recyclerViewAdapter != null) {
            recyclerViewAdapter.setFolderPolicyPickMode(false);
        }
    }

    /**
     * Media-folder-policy multi picker: Back leaves pick mode first, then walks up directories;
     * only at remote root does Back pop this fragment (return to policy screen).
     */
    private void handleMediaFolderPolicyMultiSelectBack() {
        if (!mediaFolderPolicyMultiSelectMode) {
            return;
        }
        final String currentPath = directoryObject == null ? "null" : directoryObject.getCurrentPath();
        final String parentPath = parentExplorerPathOrNull();
        final String parentForLog = parentPath == null ? "null" : parentPath;
        if (Boolean.TRUE.equals(isSearchMode)) {
            policyPickerNavLog("search", currentPath, parentForLog);
            searchClicked();
            return;
        }
        if (mediaFolderPolicyPickMode) {
            policyPickerNavLog("exitPickMode", currentPath, parentForLog);
            exitMediaFolderPolicyPickMode();
            return;
        }
        if (parentPath != null) {
            policyPickerNavLog("parentNavigation", currentPath, parentForLog);
            onBreadCrumbClicked(parentPath);
            return;
        }
        policyPickerNavLog("atRootForwardActivity", currentPath, "null");
        if (mediaFolderPolicyBackCallback != null) {
            mediaFolderPolicyBackCallback.setEnabled(false);
        }
        requireActivity().getOnBackPressedDispatcher().onBackPressed();
        if (isAdded() && mediaFolderPolicyBackCallback != null) {
            mediaFolderPolicyBackCallback.setEnabled(true);
        }
    }

    private void policyPickerNavLog(@NonNull String branch, @NonNull String currentPath, @NonNull String parentOrNullLiteral) {
        if (context == null) {
            return;
        }
        SyncLog.info(context, "PolicyPickerNavDbg",
                "event=policyPickerBack branch=" + branch
                        + " remote=" + remoteName
                        + " currentPath=" + currentPath
                        + " parent=" + parentOrNullLiteral);
    }

    @Nullable
    private String parentExplorerPathOrNull() {
        if (remoteName == null || directoryObject == null) {
            return null;
        }
        final String root = "//" + remoteName;
        final String cur = toAbsoluteUnderRemoteRoot(directoryObject.getCurrentPath(), root);
        if (cur == null) {
            return null;
        }
        if (!isStrictlyUnderRemoteRoot(cur, root)
                && !cur.equals(root)
                && !cur.equals(root + "/")) {
            return null;
        }
        if (cur.equals(root) || cur.equals(root + "/")) {
            return null;
        }
        int slash = cur.lastIndexOf('/');
        if (slash <= root.length()) {
            return root;
        }
        final String parentAbs = cur.substring(0, slash);
        return explorerAbsoluteToListingPath(parentAbs, root);
    }

    /**
     * Paths stored in {@link DirectoryObject} and used by {@code rclone lsjson} must be either
     * {@code //remoteName} at the remote root or a relative path under it (e.g. {@code Photo/Photo}).
     * Parent resolution uses absolute {@code //remoteName/...} strings; convert those before
     * {@link #onBreadCrumbClicked} / {@link DirectoryObject#setPath} so {@code getDirectoryContent}
     * does not build invalid {@code remote://remote/...} targets.
     */
    @NonNull
    private String explorerAbsoluteToListingPath(@NonNull String abs, @NonNull String root) {
        if (abs.equals(root) || abs.equals(root + "/")) {
            return root;
        }
        if (abs.startsWith(root + "/")) {
            return abs.substring(root.length() + 1);
        }
        return abs;
    }

    /**
     * Rclone lsjson uses relative {@link FileItem} paths when listing the remote root; navigation
     * then stores {@code Cosplay/sub} instead of {@code //remote/Cosplay/sub}. Media-folder Back
     * must resolve parents against the absolute {@code //remote/...} form.
     */
    @Nullable
    private String toAbsoluteUnderRemoteRoot(@Nullable String raw, @NonNull String root) {
        if (raw == null) {
            return null;
        }
        if (isStrictlyUnderRemoteRoot(raw, root) || raw.equals(root) || raw.equals(root + "/")) {
            return raw;
        }
        if (raw.startsWith("//")) {
            return raw;
        }
        if (raw.isEmpty()) {
            return root;
        }
        if (raw.charAt(0) == '/') {
            return root + raw;
        }
        return root + "/" + raw;
    }

    /**
     * True when {@code path} is {@code //remoteName} or {@code //remoteName/...}, but not a false
     * positive such as {@code //pcloudLock} when {@code remoteName} is {@code pcloud}.
     */
    private static boolean isStrictlyUnderRemoteRoot(@NonNull String path, @NonNull String root) {
        if (!path.startsWith(root)) {
            return false;
        }
        if (path.length() > root.length()) {
            return path.charAt(root.length()) == '/';
        }
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceivers();

        if (showThumbnails) {
            startThumbnailService();
        }

        if (directoryObject.isContentValid()) {
            return;
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (recyclerViewAdapter != null) {
            recyclerViewAdapter.setThumbnailHostResumed(true);
        }
    }

    @Override
    public void onPause() {
        if (recyclerViewAdapter != null) {
            recyclerViewAdapter.setThumbnailHostResumed(false);
        }
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_PATH, directoryObject.getCurrentPath());
        ArrayList<FileItem> content = new ArrayList<>(directoryObject.getDirectoryContent());
        outState.putParcelableArrayList(SAVED_CONTENT, content);
        outState.putBoolean(SAVED_START_AT_BOOT, startAtRoot);
        if (isSearchMode) {
            outState.putString(SAVED_SEARCH_STRING, searchString);
        }
        if (recyclerViewAdapter.isInSelectMode()) {
            outState.putParcelableArrayList(SAVED_SELECTED_ITEMS, new ArrayList<>(recyclerViewAdapter.getSelectedItems()));
        }
        if (mediaFolderPolicyMultiSelectMode) {
            outState.putStringArrayList(SAVED_MULTI_SELECTED_PATHS, new ArrayList<>(selectedFolderPaths));
            outState.putBoolean(SAVED_PICK_MODE, mediaFolderPolicyPickMode);
        }

        if (LargeParcel.calculateBundleSize(outState) > 250 * 1024) {
            outState.remove(SAVED_CONTENT);
        }
    }



    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }
    }

    private void setTitle() {
        ((FragmentActivity) context).setTitle(getString(R.string.remote_folder_picker_title));
    }

    private void buildStackFromPath(String remote, String path) {
        String root = "//" + remote;
        if (root.equals(path)) {
            return;
        }
        pathStack.clear();
        pathStack.push(root);

        int index = 0;

        while ((index = path.indexOf("/", index)) > 0) {
            String p = path.substring(0, index);
            pathStack.push(p);
            index++;
        }
    }

    private void registerReceivers() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(getString(R.string.background_service_broadcast));
        LocalBroadcastManager.getInstance(context).registerReceiver(backgroundTaskBroadcastReceiver, intentFilter);
    }

    private BroadcastReceiver backgroundTaskBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastRemote = intent.getStringExtra(getString(R.string.background_service_broadcast_data_remote));
            String broadcastPath = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path));
            String broadcastPath2 = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path2));
            String path = directoryObject.getCurrentPath();
            if (!remoteName.equals(broadcastRemote)) {
                return;
            }

            if (path.equals(broadcastPath)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                if (directoryObject.isPathInCache(broadcastPath)) {
                    directoryObject.removePathFromCache(broadcastPath);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath)) {
                directoryObject.removePathFromCache(broadcastPath);
            }

            if (broadcastPath2 == null) {
                return;
            }

            if (path.equals(broadcastPath2)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                swipeRefreshLayout.setRefreshing(false);
                if (directoryObject.isPathInCache(broadcastPath2)) {
                    directoryObject.removePathFromCache(broadcastPath2);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath2)) {
                directoryObject.removePathFromCache(broadcastPath2);
            }
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.remote_folder_picker_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_search:
                searchClicked();
                return true;
            case R.id.action_sort:
                showSortMenu();
                return true;
            case R.id.action_go_to:
                showSFTPgoToDialog();
                return true;
            case R.id.action_new_folder:
                onCreateNewDirectory();
                return true;
            case android.R.id.home:
                if (mediaFolderPolicyMultiSelectMode) {
                    handleMediaFolderPolicyMultiSelectBack();
                } else {
                    exitFragment();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private String directoryPathToSelectorArgument(String directoryAbsolutePath) {
        String target = "/" + directoryAbsolutePath;
        if (target.startsWith("///")) {
            target = target.replace("//", "");
        }
        return target;
    }

    private void updateMultiSelectFabUi() {
        if (fab == null || !mediaFolderPolicyMultiSelectMode || getContext() == null) {
            return;
        }
        int n = selectedFolderPaths.size();
        fab.setEnabled(n > 0);
        fab.setAlpha(n > 0 ? 1f : 0.38f);
        if (n > 0) {
            fab.setText(getString(R.string.media_folder_policy_add_n_folders, n));
            fab.extend();
        } else {
            fab.shrink();
        }
    }

    private void configureSelectFab() {
        if (mSelectedFolderCallback == null) {
            if (context != null) {
                SyncLog.info(context, "PolicyCrashDbg", "event=pickerFabNoListener remote=" + remoteName + " callback=null");
            }
            return;
        }
        if (mediaFolderPolicyMultiSelectMode) {
            fab.setOnClickListener(v -> {
                if (selectedFolderPaths.isEmpty()) {
                    return;
                }
                List<String> out = new ArrayList<>();
                for (String p : selectedFolderPaths) {
                    out.add(directoryPathToSelectorArgument(p));
                }
                SyncLog.info(context, "PolicyCrashDbg", "event=fabSelectMulti count=" + out.size());
                mSelectedFolderCallback.selectFolders(out);
                selectedFolderPaths.clear();
                exitFragment();
            });
            updateMultiSelectFabUi();
        } else {
            fab.shrink();
            fab.setOnClickListener(v -> {
                String target = directoryPathToSelectorArgument(directoryObject.getCurrentPath());
                SyncLog.info(context, "PolicyCrashDbg", "event=fabSelect path=" + target + " callback=set");
                mSelectedFolderCallback.selectFolder(target);
                exitFragment();
            });
        }
    }

    private void exitFragment() {
        Context logCtx = getContext();
        if (logCtx != null) {
            int backStack = -1;
            FragmentActivity act = getActivity();
            if (act != null) {
                backStack = act.getSupportFragmentManager().getBackStackEntryCount();
            }
            String path = directoryObject != null ? directoryObject.getCurrentPath() : "null";
            SyncLog.info(logCtx, "PolicyCrashDbg", "event=exitPicker backStack=" + backStack + " path=" + path);
        }
        breadcrumbView.clearCrumbs();
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        FragmentManager fm = this.getActivity().getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStack();
        } else {
            fm.beginTransaction().remove(this).commit();
        }
    }

    private void showSFTPgoToDialog() {
        GoToDialog goToDialog = new GoToDialog();
        goToDialog.show(getChildFragmentManager(), "go to dialog");
    }

    /*
     * Swipe to refresh
     */
    @Override
    public void onRefresh() {
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    private void startThumbnailService() {
        if (context == null || remote == null) {
            return;
        }
        thumbnailServeLeaseId = ThumbnailServerManager.getInstance().acquireServeLease(
                context, remote, thumbnailServerPort, thumbnailServerAuth);
        if (thumbnailServeLeaseId == 0) {
            return;
        }
        ThumbnailServerService.startServing(context, remote, thumbnailServerPort, thumbnailServerAuth,
                directoryObject.getCurrentPath(), true);
    }

    private void releaseThumbnailServeLease() {
        int id = thumbnailServeLeaseId;
        thumbnailServeLeaseId = 0;
        if (id != 0) {
            ThumbnailServerManager.getInstance().releaseServeLease(id);
        }
    }

    private void initializeThumbnailParams() {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[16];
        random.nextBytes(values);
        thumbnailServerAuth = Base64.encodeToString(values, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
        thumbnailServerPort = allocatePort(29179, true);
        thumbnailUrlEpoch++;
    }

    private static int allocatePort(int port, boolean allocateFallback) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            if (allocateFallback) {
                return allocatePort(0, false);
            }
        }
        throw new IllegalStateException("No port available");
    }

    private void searchClicked() {
        if (isSearchMode) {
            //breadcrumbView.setVisibility(View.VISIBLE);
            searchDirContent("");
            recyclerViewAdapter.setSearchMode(false);
            isSearchMode = false;
        } else {
            //breadcrumbView.setVisibility(View.GONE);
            recyclerViewAdapter.setSearchMode(true);
            isSearchMode = true;
        }
    }


    private void searchDirContent(String search) {
        List<FileItem> content = directoryObject.getDirectoryContent();
        List<FileItem> currentShown = recyclerViewAdapter.getCurrentContent();
        List<FileItem> results = new ArrayList<>();

        searchString = search;

        if (search.isEmpty()) {
            if (currentShown.equals(content)) {
                return;
            } else {
                recyclerViewAdapter.newData(content);
            }
        }

        for (FileItem item : content) {
            String fileName = item.getName().toLowerCase();
            if (fileName.contains(search.toLowerCase())) {
                results.add(item);
            }
        }

        if (currentShown.equals(results)) {
            return;
        }
        recyclerViewAdapter.newData(results);
    }

    private void showSortMenu() {
        SortDialog sortDialog = new SortDialog();
        sortDialog
                .setTitle(R.string.sort)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.ok)
                .setSortOrder(sortOrder);
        sortDialog.show(getChildFragmentManager(), "sort dialog");
    }


    private void onCreateNewDirectory() {
        new InputDialog()
                .setTitle(R.string.create_new_folder)
                .setMessage(R.string.type_new_folder_name)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.okay_confirmation)
                .setHint(R.string.hint_new_folder)
                .show(getChildFragmentManager(), "input dialog");
    }

    /*
     * Input Dialog callback
     */
    @Override
    public void onPositive(String tag, String input) {
        if (input.trim().length() == 0) {
            return;
        }
        String newDir;
        if (directoryObject.getCurrentPath().equals("//" + remote.getName())) {
            newDir = input;
        } else {
            newDir = directoryObject.getCurrentPath() + "/" + input;
        }
        rclone.makeDirectory(remote, newDir);
    }


    /*
     * Sort Dialog callback
     */
    @Override
    public void onPositiveButtonClick(int sortById, int sortOrderId) {
        if (!directoryObject.isDirectoryContentEmpty()) {
            sortSelected(sortById, sortOrderId);
        }
    }

    private void sortSelected(int sortById, int sortOrderId) {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();

        switch (sortById) {
            case R.id.radio_sort_name:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                    sortOrder = SortDialog.ALPHA_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                    sortOrder = SortDialog.ALPHA_DESCENDING;
                }
                break;
            case R.id.radio_sort_date:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                    sortOrder = SortDialog.MOD_TIME_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                    sortOrder = SortDialog.MOD_TIME_DESCENDING;
                }
                break;
            case R.id.radio_sort_size:
                if (sortOrderId == R.id.radio_sort_ascending) {
                    Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                    sortOrder = SortDialog.SIZE_ASCENDING;
                } else {
                    Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                    sortOrder = SortDialog.SIZE_DESCENDING;
                }
                break;
        }
        directoryObject.setContent(directoryContent);

        if (isSearchMode) {
            List<FileItem> sortedSearch = new ArrayList<>();
            List<FileItem> searchResult = recyclerViewAdapter.getCurrentContent();
            for (FileItem item : directoryContent) {
                if (searchResult.contains(item)) {
                    sortedSearch.add(item);
                }
            }
            recyclerViewAdapter.updateSortedData(sortedSearch);
        } else {
            recyclerViewAdapter.updateSortedData(directoryContent);
        }
        if (sortOrder > 0) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            sharedPreferences.edit().putInt(SHARED_PREFS_SORT_ORDER, sortOrder).apply();
        }
    }

    private void sortDirectory() {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();
        switch (sortOrder) {
            case SortDialog.MOD_TIME_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeDescending());
                sortOrder = SortDialog.MOD_TIME_ASCENDING;
                break;
            case SortDialog.MOD_TIME_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortModTimeAscending());
                sortOrder = SortDialog.MOD_TIME_DESCENDING;
                break;
            case SortDialog.SIZE_DESCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeDescending());
                sortOrder = SortDialog.SIZE_ASCENDING;
                break;
            case SortDialog.SIZE_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortSizeAscending());
                sortOrder = SortDialog.SIZE_DESCENDING;
                break;
            case SortDialog.ALPHA_ASCENDING:
                Collections.sort(directoryContent, new FileComparators.SortAlphaAscending());
                sortOrder = SortDialog.ALPHA_ASCENDING;
                break;
            case SortDialog.ALPHA_DESCENDING:
            default:
                Collections.sort(directoryContent, new FileComparators.SortAlphaDescending());
                sortOrder = SortDialog.ALPHA_DESCENDING;
        }
        directoryObject.setContent(directoryContent);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @Override
    public void onStop() {
        super.onStop();
        releaseThumbnailServeLease();
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        LocalBroadcastManager.getInstance(context).unregisterReceiver(backgroundTaskBroadcastReceiver);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (recyclerViewAdapter != null) {
            recyclerViewAdapter.setMultiFolderPickerDelegate(null);
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        breadcrumbView.clearCrumbs();
        breadcrumbView.setVisibility(View.GONE);
        prefChangeListener = null;
        context = null;
    }

    @Override
    public void onFileClicked(FileItem fileItem) {}

    @Override
    public void onDirectoryClicked(FileItem fileItem, int position) {
        directoryPosition.put(directoryObject.getCurrentPath(), position);
        breadcrumbView.addCrumb(fileItem.getName(), fileItem.getPath());
        swipeRefreshLayout.setRefreshing(true);
        pathStack.push(directoryObject.getCurrentPath());

        if (isSearchMode) {
            searchClicked();
        }

        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }

        if (!directoryObject.isContentValid(fileItem.getPath())) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(fileItem.getPath())) {
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            swipeRefreshLayout.setRefreshing(false);
        } else {
            directoryObject.setPath(fileItem.getPath());
            recyclerViewAdapter.clear();
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @Override
    public void onFilesSelected() {}
    @Override
    public void onFileDeselected() {}
    @Override
    public void onFileOptionsClicked(View view, FileItem fileItem) {}

    @Override
    public String[] getThumbnailServerParams() {
        return new String[]{thumbnailServerAuth + '/' + remote.getName(), String.valueOf(thumbnailServerPort)};
    }

    @Override
    public int getThumbnailUrlEpoch() {
        return thumbnailUrlEpoch;
    }

    @Override
    public void onBreadCrumbClicked(String path) {
        if (isSearchMode) {
            searchClicked();
        }
        if (remoteName != null) {
            path = explorerAbsoluteToListingPath(
                    toAbsoluteUnderRemoteRoot(path, "//" + remoteName),
                    "//" + remoteName);
        }
        final String currentPath = directoryObject.getCurrentPath();
        final boolean noopEqualsCurrent = currentPath.equals(path);
        if (context != null) {
            SyncLog.info(context, "PolicyPickerNavDbg",
                    "event=policyPickerBreadcrumb requestedPath=" + path
                            + " currentPath=" + currentPath
                            + " noopEqualsCurrent=" + noopEqualsCurrent);
        }
        if (noopEqualsCurrent) {
            return;
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        directoryObject.setPath(path);
        //noinspection StatementWithEmptyBody
        while (!pathStack.empty() && !pathStack.pop().equals(path)) {
            // pop stack until we find path
        }
        breadcrumbView.removeCrumbsUpTo(path);
        recyclerViewAdapter.clear();

        if (!directoryObject.isContentValid(path)) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(path)) {
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
        } else {
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }


    /*
     * Go To Dialog Callback
     */
    @Override
    public void onRootClicked(boolean isSetAsDefault) {
        startAtRoot = true;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), true);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    /*
     * Go To Dialog Callback
     */
    @Override
    public void onHomeClicked(boolean isSetAsDefault) {
        startAtRoot = false;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), false);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    public void setInitialPath(String initialPath) {
        mInitialPath = initialPath;
    }
    public void setSelectedFolderCallback(FolderSelectorCallback fsc) {
        mSelectedFolderCallback = fsc;
    }

    /***********************************************************************************************
     * AsyncTask classes
     ***********************************************************************************************/
    @SuppressLint("StaticFieldLeak")
    private class FetchDirectoryContent extends AsyncTask<Void, Void, List<FileItem>> {

        private boolean silentFetch;

        FetchDirectoryContent() {
            this(false);
        }

        FetchDirectoryContent(boolean silentFetch) {
            this.silentFetch = silentFetch;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(true);
            }
        }

        @Override
        protected List<FileItem> doInBackground(Void... voids) {
            List<FileItem> fileItemList;
            fileItemList = rclone.getDirectoryContent(remote, directoryObject.getCurrentPath(), startAtRoot);
            return fileItemList;
        }

        @Override
        protected void onPostExecute(List<FileItem> fileItems) {
            super.onPostExecute(fileItems);
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
            if (null == getContext()) {
                FLog.w(TAG, "FetchDirectoryContent/onPostExecute: discarding refresh");
                return;
            }
            if (fileItems == null) {
                if (silentFetch) {
                    return;
                }
                FLog.w(TAG, "FetchDirectoryContent/onPostExecute: "+getString(R.string.error_getting_dir_content));
                Toasty.error(context, getString(R.string.error_getting_dir_content), Toast.LENGTH_SHORT, true).show();
                fileItems = new ArrayList<>();
            }

            directoryObject.setContent(fileItems);
            sortDirectory();

            if (isSearchMode && searchString != null) {
                searchDirContent(searchString);
            } else {
                if (recyclerViewAdapter != null) {
                    if (silentFetch) {
                        recyclerViewAdapter.updateData(directoryObject.getDirectoryContent());
                    } else {
                        recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
                    }
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }
    }
}
