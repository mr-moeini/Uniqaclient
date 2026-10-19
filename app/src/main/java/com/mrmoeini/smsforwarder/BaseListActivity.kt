package com.mrmoeini.smsforwarder

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Shared iOS-style list screen: large title, add + log buttons, permission banner, empty state,
 * rule cards with a switch, tap to edit, swipe to delete.
 */
abstract class BaseListActivity<T : Any> : AppCompatActivity() {

    protected val items = mutableListOf<T>()
    protected lateinit var adapter: SimpleRuleAdapter<T>

    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private var userTappedBanner = false
    private var contactCallback: ((String, String) -> Unit)? = null

    protected abstract val titleRes: Int
    protected abstract val requiredPermissions: Array<String>
    protected abstract fun loadItems(): MutableList<T>
    protected abstract fun saveItems()
    protected abstract fun idOf(item: T): String
    protected abstract fun titleOf(item: T): String
    protected abstract fun detailOf(item: T): String
    protected abstract fun enabledOf(item: T): Boolean
    protected abstract fun withEnabled(item: T, enabled: Boolean): T
    protected abstract fun showEditor(existing: T?)
    protected open fun onItemsChanged() {}
    protected open fun onItemRemoved(item: T) {}
    protected open fun setUpExtra(container: LinearLayout) {}

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshUi()
            if (userTappedBanner && missingPermissions().isNotEmpty()) openAppSettings()
            userTappedBanner = false
        }

    private val contactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri: Uri? = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val number = Rule.toAsciiNumber(c.getString(0) ?: "")
                        val name = c.getString(1) ?: ""
                        contactCallback?.invoke(number, name)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        findViewById<View>(R.id.btnLog).setOnClickListener { showLog() }

        items.addAll(loadItems())
        adapter = SimpleRuleAdapter(
            items,
            { titleOf(it) },
            { detailOf(it) },
            { enabledOf(it) },
            { item, checked ->
                val i = items.indexOfFirst { idOf(it) == idOf(item) }
                if (i >= 0) {
                    items[i] = withEnabled(items[i], checked)
                    saveItems()
                    onItemsChanged()
                }
            },
            { showEditor(it) }
        )

        val rv = findViewById<RecyclerView>(R.id.rvRules)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        attachSwipeToDelete(rv)

        setUpExtra(findViewById<LinearLayout>(R.id.extraContainer))

        tvStatus.setOnClickListener {
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                openAppSettings()
            } else {
                userTappedBanner = true
                permLauncher.launch(missing.toTypedArray())
            }
        }

        val missing = missingPermissions()
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    protected fun missingPermissions(): List<String> = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    protected fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    protected fun refreshUi() {
        val missing = missingPermissions()
        tvStatus.text = if (missing.isEmpty()) {
            getString(R.string.perm_missing)
        } else {
            getString(R.string.perm_missing) + "\n(" +
                missing.joinToString(", ") { it.substringAfterLast('.') } + ")"
        }
        tvStatus.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    protected fun persist() {
        saveItems()
        adapter.notifyDataSetChanged()
        refreshUi()
        onItemsChanged()
    }

    /** Opens the system contact picker (no READ_CONTACTS permission needed) and returns number + name. */
    protected fun pickContact(callback: (String, String) -> Unit) {
        contactCallback = callback
        try {
            contactLauncher.launch(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.err_no_contacts, Toast.LENGTH_SHORT).show()
        }
    }

    protected fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(item: T, onCancel: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setPositiveButton(R.string.delete) { _, _ ->
                items.removeAll { idOf(it) == idOf(item) }
                persist()
                onItemRemoved(item)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun showLog() {
        val lines = RuleStore.readLog(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.log)
            .setMessage(if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n\n"))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear_log) { _, _ -> RuleStore.clearLog(this) }
            .show()
    }

    /** iOS-style swipe left/right on a card to reveal a red Delete action. */
    private fun attachSwipeToDelete(rv: RecyclerView) {
        val density = resources.displayMetrics.density
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@BaseListActivity, R.color.ios_red)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= items.size) return
                confirmDelete(items[pos]) { adapter.notifyItemChanged(pos) }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val v = viewHolder.itemView
                if (dX != 0f) {
                    val r = if (dX < 0) {
                        RectF(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
                    } else {
                        RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat())
                    }
                    c.drawRoundRect(r, 12 * density, 12 * density, bgPaint)
                    if (r.width() > 72 * density) {
                        val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                        c.drawText(getString(R.string.delete), r.centerX(), y, textPaint)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(rv)
    }
}
