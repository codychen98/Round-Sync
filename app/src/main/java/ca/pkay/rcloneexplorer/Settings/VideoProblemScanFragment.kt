package ca.pkay.rcloneexplorer.Settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Activities.VideoProblemScanActivity
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.VideoProblemScanCache
import ca.pkay.rcloneexplorer.util.VideoProblemScanExport
import ca.pkay.rcloneexplorer.util.VideoProblemVerdict
import ca.pkay.rcloneexplorer.workmanager.VideoProblemScanWorker
import es.dmoral.toasty.Toasty
import java.io.File

class VideoProblemScanFragment : Fragment() {

    private lateinit var rclone: Rclone
    private var remotes: List<RemoteItem> = emptyList()
    private lateinit var spinner: Spinner
    private lateinit var folderPathView: TextView
    private lateinit var progressView: TextView
    private lateinit var resultsView: TextView
    private lateinit var chooseFolderButton: Button
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var shareButton: Button
    private var selectedRemoteIndex: Int = 0
    private var selectedFolderPath: String = ""
    private var workObserver: Observer<List<WorkInfo>>? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        rclone = Rclone(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_video_problem_scan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        spinner = view.findViewById(R.id.video_problem_scan_remote_spinner)
        folderPathView = view.findViewById(R.id.video_problem_scan_folder_path)
        progressView = view.findViewById(R.id.video_problem_scan_progress)
        resultsView = view.findViewById(R.id.video_problem_scan_results)
        chooseFolderButton = view.findViewById(R.id.video_problem_scan_choose_folder)
        startButton = view.findViewById(R.id.video_problem_scan_start)
        cancelButton = view.findViewById(R.id.video_problem_scan_cancel)
        shareButton = view.findViewById(R.id.video_problem_scan_share)

        if (savedInstanceState != null) {
            selectedRemoteIndex = savedInstanceState.getInt(STATE_REMOTE_INDEX, 0)
            selectedFolderPath = savedInstanceState.getString(STATE_FOLDER_PATH, "") ?: ""
        }

        bindRemoteSpinner()
        updateFolderLabel()
        chooseFolderButton.setOnClickListener { openFolderPicker() }
        startButton.setOnClickListener { startScan() }
        cancelButton.setOnClickListener { cancelScan() }
        shareButton.setOnClickListener { shareReport() }
        loadLastReport()
        observeWork()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_REMOTE_INDEX, selectedRemoteIndex)
        outState.putString(STATE_FOLDER_PATH, selectedFolderPath)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.title_activity_video_problem_scan)
        if (view != null) {
            updateFolderLabel()
        }
    }

    override fun onDestroyView() {
        workObserver?.let { observer ->
            WorkManager.getInstance(requireContext().applicationContext)
                .getWorkInfosForUniqueWorkLiveData(VideoProblemScanWorker.UNIQUE_WORK_NAME)
                .removeObserver(observer)
        }
        workObserver = null
        super.onDestroyView()
    }

    fun handleFolderSelectedFromPicker(path: String) {
        selectedFolderPath = path.trim()
        if (view != null) {
            updateFolderLabel()
        }
    }

    private fun bindRemoteSpinner() {
        val list = ArrayList(rclone.remotes)
        RemoteItem.prepareDisplay(requireContext(), list)
        list.sortWith { a, b -> a.displayName.compareTo(b.displayName) }
        remotes = list
        if (list.isEmpty()) {
            spinner.isEnabled = false
            chooseFolderButton.isEnabled = false
            startButton.isEnabled = false
            folderPathView.setText(R.string.video_problem_scan_no_remotes)
            return
        }
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            list.map { it.displayName },
        )
        selectedRemoteIndex = selectedRemoteIndex.coerceIn(0, list.size - 1)
        spinner.setSelection(selectedRemoteIndex, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (selectedRemoteIndex != position) {
                    selectedRemoteIndex = position
                    selectedFolderPath = ""
                    updateFolderLabel()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    private fun selectedRemote(): RemoteItem? {
        if (remotes.isEmpty()) {
            return null
        }
        return remotes[selectedRemoteIndex.coerceIn(0, remotes.size - 1)]
    }

    private fun updateFolderLabel() {
        val remote = selectedRemote()
        val label = when {
            remote == null -> getString(R.string.video_problem_scan_no_remotes)
            selectedFolderPath.isEmpty() -> getString(R.string.video_problem_scan_folder_unset)
            else -> "//${remote.name}/$selectedFolderPath"
        }
        folderPathView.text = label
    }

    private fun openFolderPicker() {
        val remote = selectedRemote() ?: return
        val act = requireActivity() as? VideoProblemScanActivity ?: return
        act.launchRemoteFolderPicker(remote)
    }

    private fun startScan() {
        val remote = selectedRemote()
        if (remote == null) {
            return
        }
        if (selectedFolderPath.isEmpty()) {
            Toasty.info(
                requireContext(),
                getString(R.string.video_problem_scan_pick_folder_first),
                Toast.LENGTH_SHORT,
                true,
            ).show()
            return
        }
        VideoProblemScanWorker.enqueue(requireContext(), remote.name, selectedFolderPath)
        setScanningUi(true)
        progressView.setText(R.string.video_problem_scan_starting)
    }

    private fun cancelScan() {
        VideoProblemScanWorker.cancel(requireContext())
        progressView.setText(R.string.video_problem_scan_cancelling)
    }

    private fun observeWork() {
        val live = WorkManager.getInstance(requireContext().applicationContext)
            .getWorkInfosForUniqueWorkLiveData(VideoProblemScanWorker.UNIQUE_WORK_NAME)
        val observer = Observer<List<WorkInfo>> { infos ->
            val info = infos.firstOrNull() ?: return@Observer
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    setScanningUi(true)
                    val progress = info.progress
                    val done = progress.getInt(VideoProblemScanWorker.KEY_DONE, 0)
                    val total = progress.getInt(VideoProblemScanWorker.KEY_TOTAL, 0)
                    val problems = progress.getInt(VideoProblemScanWorker.KEY_PROBLEMS, 0)
                    if (total > 0) {
                        progressView.text = getString(
                            R.string.video_problem_scan_progress_line,
                            done,
                            total,
                            problems,
                        )
                    } else {
                        progressView.setText(R.string.video_problem_scan_starting)
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    setScanningUi(false)
                    val cancelled = info.outputData.getBoolean(VideoProblemScanWorker.KEY_CANCELLED, false)
                    loadLastReport()
                    if (cancelled) {
                        progressView.setText(R.string.video_problem_scan_cancelled)
                    }
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    setScanningUi(false)
                    val err = info.outputData.getString(VideoProblemScanWorker.KEY_ERROR)
                    progressView.text = if (err.isNullOrEmpty()) {
                        getString(R.string.video_problem_scan_failed)
                    } else {
                        getString(R.string.video_problem_scan_failed_detail, err)
                    }
                    loadLastReport()
                }
                else -> {
                }
            }
        }
        workObserver = observer
        live.observe(viewLifecycleOwner, observer)
    }

    private fun setScanningUi(scanning: Boolean) {
        startButton.isEnabled = !scanning
        cancelButton.isEnabled = scanning
        chooseFolderButton.isEnabled = !scanning
        spinner.isEnabled = !scanning
    }

    private fun loadLastReport() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val report = VideoProblemScanCache.readLastReport(prefs)
        if (report == null) {
            resultsView.text = ""
            shareButton.isEnabled = false
            return
        }
        progressView.text = getString(
            R.string.video_problem_scan_summary,
            report.scannedCount,
            report.skippedUnchanged,
            report.problemRows.size,
        )
        resultsView.text = formatGroupedResults(report.problemRows)
        shareButton.isEnabled = report.problemRows.isNotEmpty()
    }

    private fun formatGroupedResults(rows: List<VideoProblemScanExport.ProblemRow>): CharSequence {
        if (rows.isEmpty()) {
            return getString(R.string.video_problem_scan_no_problems)
        }
        val sb = StringBuilder()
        val groups = linkedMapOf(
            VideoProblemVerdict.AUDIO to ArrayList<VideoProblemScanExport.ProblemRow>(),
            VideoProblemVerdict.SEEK to ArrayList(),
            VideoProblemVerdict.BOTH to ArrayList(),
            VideoProblemVerdict.PROBE_FAILED to ArrayList(),
        )
        for (row in rows) {
            groups[row.verdict]?.add(row)
        }
        for ((verdict, groupRows) in groups) {
            if (groupRows.isEmpty()) {
                continue
            }
            sb.append(verdict.name).append(" (").append(groupRows.size).append(")\n")
            for (row in groupRows) {
                sb.append(row.relativePath)
                if (row.detail.isNotEmpty()) {
                    sb.append(" — ").append(row.detail)
                }
                sb.append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun shareReport() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val report = VideoProblemScanCache.readLastReport(prefs) ?: return
        val body = VideoProblemScanExport.formatReport(report.problemRows)
        if (body.isEmpty()) {
            return
        }
        try {
            val dir = File(requireContext().cacheDir, "report")
            if (!dir.exists() && !dir.mkdirs()) {
                Toasty.error(
                    requireContext(),
                    getString(R.string.video_problem_scan_share_failed),
                    Toast.LENGTH_SHORT,
                    true,
                ).show()
                return
            }
            val file = File(dir, "video_problem_scan.txt")
            file.writeText(body)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                BuildConfig.APPLICATION_ID + ".fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.video_problem_scan_share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.video_problem_scan_share)))
        } catch (_: Exception) {
            Toasty.error(
                requireContext(),
                getString(R.string.video_problem_scan_share_failed),
                Toast.LENGTH_SHORT,
                true,
            ).show()
        }
    }

    companion object {
        const val TAG = "VideoProblemScanFragment"
        private const val STATE_REMOTE_INDEX = "remote_index"
        private const val STATE_FOLDER_PATH = "folder_path"
    }
}
