package io.github.detach

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.Checkable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
object PaymentAppPicker {
    fun show(activity: Activity, onSaved: () -> Unit) {
        show(activity, Mode.PAYMENT, onSaved)
    }

    fun showBlockedApps(activity: Activity, onSaved: () -> Unit) {
        show(activity, Mode.BLOCKED, onSaved)
    }

    internal fun showFocusApps(activity: Activity, onSaved: () -> Unit) {
        show(activity, Mode.FOCUS, onSaved)
    }

    internal fun showDistractingApps(activity: Activity, onSaved: () -> Unit) {
        show(activity, Mode.DISTRACTION, onSaved)
    }

    private fun show(activity: Activity, mode: Mode, onSaved: () -> Unit) {
        if (!activity.isFinishing && !activity.isDestroyed) Session(activity, mode, onSaved).show()
    }

    private enum class Mode(val title: String) {
        PAYMENT("Auto-pause apps"),
        BLOCKED("Select apps to block"),
        FOCUS("Allowed during Focus"),
        DISTRACTION("Choose distracting apps"),
    }

    internal data class App(val info: ApplicationInfo, val name: String) {
        val searchName = name.lowercase(Locale.ROOT)
    }

    internal fun loadApps(manager: PackageManager, ownPackage: String, saved: Set<String>, blocking: Boolean = false): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val packages = manager.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.packageName }.toSet() +
            (if (blocking) manager.queryIntentActivities(leanback, 0).mapNotNull { it.activityInfo?.packageName } else emptyList()) +
            saved + if (blocking) emptySet() else Targets.PAYMENT_DEFAULTS
        val relevant = packages.filter {
            it != ownPackage && (blocking || it !in Targets.READABLE) && it !in Targets.SYSTEM_UI
        }
        return appsFromPackages(manager, relevant, blocking)
    }

    internal fun appsFromPackages(manager: PackageManager, packages: Iterable<String>, blocking: Boolean): List<App> {
        val collator = Collator.getInstance()
        return packages.mapNotNull { packageName ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            runCatching {
                val info = manager.getApplicationInfo(packageName, 0)
                val name = manager.getApplicationLabel(info).toString().trim()
                if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) null
                else if (blocking) App(info, name.ifBlank { packageName })
                else if (info.icon == 0 || name.isBlank() || name == packageName || PACKAGE_LABEL.matches(name)) null
                else App(info, name)
            }.getOrNull()
        }.sortedWith { first, second ->
            collator.compare(first.name, second.name).takeIf { it != 0 }
                ?: first.info.packageName.compareTo(second.info.packageName)
        }
    }

    internal fun loadIcon(manager: PackageManager, app: App, size: Int): Bitmap? = runCatching {
        val drawable = if (app.info.icon != 0) manager.getDrawable(app.info.packageName, app.info.icon, app.info)
            else manager.getApplicationIcon(app.info)
        drawable ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = minOf(size.toFloat() / width, size.toFloat() / height)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val left = (size - targetWidth) / 2
        val top = (size - targetHeight) / 2
        drawable.setBounds(left, top, left + targetWidth, top + targetHeight)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    private val PACKAGE_LABEL = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    private class Session(private val activity: Activity, private val mode: Mode, private var onSaved: (() -> Unit)?) :
        Application.ActivityLifecycleCallbacks {
        private val ui = Ui(activity)
        private val settings = Settings(activity)
        private val selected = when (mode) {
            Mode.PAYMENT -> settings.paymentPackages
            Mode.BLOCKED -> settings.blockedPackages
            Mode.FOCUS -> settings.focusAllowedPackages
            Mode.DISTRACTION -> ScreenTimePreferences(activity).distractingApps
        }.toMutableSet()
        private val focus = mode == Mode.FOCUS
        private val distraction = mode == Mode.DISTRACTION
        private val tagPrefix = when {
            focus -> "focus-app"
            distraction -> "distraction-app"
            else -> "payment-app"
        }
        private val handler = Handler(Looper.getMainLooper())
        private val executor = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(24)) { task ->
            Thread(task, tagPrefix).apply { isDaemon = true }
        }
        private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        private val pendingIcons = mutableSetOf<String>()
        private val failedIcons = mutableSetOf<String>()
        private var apps = emptyList<App>()
        private var visibleApps = emptyList<App>()
        private var duplicateNames = emptySet<String>()
        private var closed = false
        private var loaded = false
        private val iconSize = ui.dp(40).coerceIn(1, 192)
        private val search = ui.style(EditText(activity)).apply {
            tag = "$tagPrefix-search"
            hint = "Search installed apps"
            contentDescription = "Search installed apps"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            backgroundTintList = null
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ui.shape(ui.fieldSurface, ui.accent, 15))
                addState(intArrayOf(), ui.shape(ui.fieldSurface, ui.line, 15))
            }
            setPaddingRelative(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            val glyph = activity.getDrawable(R.drawable.ic_search)?.mutate()?.apply { setTint(ui.sub) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(glyph, null, null, null)
            compoundDrawablePadding = ui.dp(8)
        }
        private val summary = ui.note("").apply {
            tag = "$tagPrefix-summary"
            setPadding(0, ui.dp(8), 0, ui.dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val status = ui.text("Loading installed apps…", 15f, ui.sub).apply {
            tag = "$tagPrefix-status"
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val adapter = object : BaseAdapter() {
            override fun getCount() = visibleApps.size
            override fun getItem(position: Int) = visibleApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = !focus
            override fun isEnabled(position: Int) = !isRequired(getItem(position).info.packageName)
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = getItem(position)
                val row = convertView as? AppRow ?: AppRow(activity, ui)
                val packageName = app.info.packageName
                val required = isRequired(packageName)
                val detail = listOfNotNull(
                    "Always allowed".takeIf { required },
                    packageName.takeIf { app.searchName in duplicateNames },
                ).joinToString(" · ")
                row.bind(app.name, detail, packageName in selected, required, icons.get(packageName))
                requestIcon(app)
                return row
            }
        }
        private val clear = if (!focus && !distraction) null else ui.quiet(if (focus) "Clear optional" else "Clear all") {
            selected.removeAll { !isRequired(it) }
            adapter.notifyDataSetChanged()
            updateSummary()
        }
        private val list = ListView(activity).apply {
            tag = "$tagPrefix-list"
            divider = null
            adapter = this@Session.adapter
            emptyView = status
            setOnItemClickListener { _, view, position, _ ->
                val packageName = visibleApps[position].info.packageName
                if (isRequired(packageName)) return@setOnItemClickListener
                if (!selected.add(packageName)) selected.remove(packageName)
                if (mode == Mode.BLOCKED && packageName !in selected && packageName in settings.blockedPackages &&
                    BreatherBudget.restrictionsLocked(activity)) {
                    selected.add(packageName)
                    android.widget.Toast.makeText(activity, "Blocked apps are locked until the daily allowance resets.", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (mode == Mode.PAYMENT && packageName in selected && packageName !in settings.paymentPackages &&
                    BreatherBudget.restrictionsLocked(activity)) {
                    selected.remove(packageName)
                    android.widget.Toast.makeText(activity, "Payment app additions are locked until the daily allowance resets.", android.widget.Toast.LENGTH_SHORT).show()
                }
                (view as Checkable).isChecked = packageName in selected
                updateSummary()
            }
        }
        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(text: Editable?) = Unit
        }
        private val listHeight = (activity.resources.displayMetrics.heightPixels * 0.42f).toInt()
        private val listPanel = FrameLayout(activity).apply {
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        private val visibleFrame = Rect()
        private val resizeList = ViewTreeObserver.OnGlobalLayoutListener { resizeForKeyboard() }

        private fun resizeForKeyboard(keyboardInset: Int = 0) {
            if (closed || listPanel.height == 0) return
            val decor = listPanel.rootView
            decor.getWindowVisibleDisplayFrame(visibleFrame)
            if (visibleFrame.isEmpty) return
            val chromeHeight = decor.height - listPanel.height
            val available = if (Build.VERSION.SDK_INT >= 30) {
                val keyboard = maxOf(keyboardInset, decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)
                val windowHeight = activity.windowManager.currentWindowMetrics.bounds.height()
                minOf(visibleFrame.height(), windowHeight - keyboard)
            } else visibleFrame.height()
            val height = minOf(listHeight, (available - chromeHeight - ui.dp(16)).coerceAtLeast(ui.dp(48)))
            if (listPanel.layoutParams.height != height) {
                listPanel.layoutParams = listPanel.layoutParams.apply { this.height = height }
            }
        }
        private val dialog = AlertDialog.Builder(activity)
            .setTitle(mode.title)
            .setView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0)
                if (distraction) {
                    addView(ui.note("Leave study, work, and other useful apps unchecked."))
                    addView(ui.space(8))
                }
                addView(search)
                if (clear == null) {
                    addView(summary)
                } else {
                    addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    })
                }
                addView(listPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
            })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun show() {
            activity.application.registerActivityLifecycleCallbacks(this)
            dialog.setOnDismissListener { close() }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.show()
            ui.style(dialog)
            dialog.window?.decorView?.let { decor ->
                decor.viewTreeObserver.addOnGlobalLayoutListener(resizeList)
                if (Build.VERSION.SDK_INT >= 30) {
                    // A floating window can receive new IME insets without a new global layout.
                    decor.setOnApplyWindowInsetsListener { view, insets ->
                        val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                        view.post { resizeForKeyboard(keyboard) }
                        view.onApplyWindowInsets(insets)
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    if (closed || !loaded || activity.isFinishing || activity.isDestroyed) return@setOnClickListener
                    when (mode) {
                        Mode.BLOCKED -> settings.blockedPackages =
                            if (BreatherBudget.restrictionsLocked(activity)) selected + settings.blockedPackages else selected
                        Mode.PAYMENT -> settings.paymentPackages =
                            if (BreatherBudget.restrictionsLocked(activity)) selected intersect settings.paymentPackages else selected
                        Mode.FOCUS -> {
                            if (FocusSession.active(activity)) {
                                dialog.dismiss()
                                return@setOnClickListener
                            }
                            settings.focusAllowedPackages = selected
                        }
                        Mode.DISTRACTION -> ScreenTimePreferences(activity).distractingApps = selected
                    }
                    val callback = onSaved
                    dialog.dismiss()
                    callback?.invoke()
                }
            }
            search.addTextChangedListener(watcher)
            updateSummary()
            val owner = WeakReference(this)
            val main = handler
            val context = activity.applicationContext
            val saved = selected.toSet()
            executor.execute {
                val result = runCatching {
                    if (focus) FocusAppPicker.pickableApps(context, saved)
                    else loadApps(context.packageManager, context.packageName, saved, mode != Mode.PAYMENT)
                }
                main.post { owner.get()?.receiveApps(result) }
            }
        }

        private fun receiveApps(result: Result<List<App>>) {
            if (closed) return
            loaded = result.isSuccess
            apps = result.getOrDefault(emptyList())
            if (focus || distraction) duplicateNames = apps.groupBy { it.searchName }.filterValues { it.size > 1 }.keys
            filter()
            updateSummary()
            if (!loaded) status.text = "Couldn’t load installed apps. Please try again."
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = loaded
        }

        private fun filter() {
            val query = search.text.toString().trim().lowercase(Locale.ROOT)
            visibleApps = if (query.isEmpty()) apps else apps.filter {
                query in it.searchName || (focus || distraction) && query in it.info.packageName.lowercase(Locale.ROOT)
            }
            if (loaded) status.text = if (query.isEmpty()) "No installed apps available" else "No matching apps"
            adapter.notifyDataSetChanged()
        }

        private fun updateSummary() {
            if (focus) {
                val count = apps.count { it.info.packageName in selected }
                summary.text = when {
                    !loaded -> ""
                    count == 1 -> "1 app allowed"
                    else -> "$count apps allowed"
                }
                clear?.isEnabled = loaded && selected.any { !isRequired(it) }
            } else if (distraction) {
                val unavailable = selected.size - apps.count { it.info.packageName in selected }
                summary.text = if (!loaded) "" else "${selected.size} selected" +
                    if (unavailable > 0) " · $unavailable unavailable" else ""
                clear?.isEnabled = loaded && selected.isNotEmpty()
            } else {
                summary.text = "${selected.size} selected. Saved choices for unavailable apps are kept."
            }
        }

        private fun isRequired(packageName: String) = focus && settings.focusRequired(packageName)

        private fun requestIcon(app: App) {
            val packageName = app.info.packageName
            if (closed || icons.get(packageName) != null || packageName in failedIcons || !pendingIcons.add(packageName)) return
            val owner = WeakReference(this)
            val main = handler
            val manager = activity.applicationContext.packageManager
            val size = iconSize
            try {
                executor.execute {
                    val bitmap = if (Thread.currentThread().isInterrupted) null else loadIcon(manager, app, size)
                    main.post { owner.get()?.receiveIcon(packageName, bitmap) }
                }
            } catch (_: RejectedExecutionException) {
                pendingIcons.remove(packageName)
            }
        }

        private fun receiveIcon(packageName: String, bitmap: Bitmap?) {
            if (closed) return
            pendingIcons.remove(packageName)
            if (bitmap != null) {
                icons.put(packageName, bitmap)
                adapter.notifyDataSetChanged()
            } else {
                if (mode != Mode.PAYMENT) {
                    failedIcons.add(packageName)
                } else {
                    apps = apps.filterNot { it.info.packageName == packageName }
                    filter()
                }
            }
        }

        private fun close() {
            if (closed) return
            closed = true
            activity.application.unregisterActivityLifecycleCallbacks(this)
            dialog.window?.decorView?.let { decor ->
                decor.viewTreeObserver.removeOnGlobalLayoutListener(resizeList)
                if (Build.VERSION.SDK_INT >= 30) decor.setOnApplyWindowInsetsListener(null)
            }
            search.removeTextChangedListener(watcher)
            list.adapter = null
            list.onItemClickListener = null
            executor.shutdownNow()
            handler.removeCallbacksAndMessages(null)
            pendingIcons.clear()
            icons.evictAll()
            onSaved = null
        }

        override fun onActivityDestroyed(host: Activity) {
            if (host === activity) {
                dialog.dismiss()
                close()
            }
        }

        override fun onActivityCreated(host: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(host: Activity) = Unit
        override fun onActivityResumed(host: Activity) = Unit
        override fun onActivityPaused(host: Activity) = Unit
        override fun onActivityStopped(host: Activity) {
            if (host === activity) {
                dialog.dismiss()
                close()
            }
        }
        override fun onActivitySaveInstanceState(host: Activity, state: Bundle) = Unit
    }

    private class AppRow(activity: Activity, ui: Ui) : LinearLayout(activity), Checkable {
        private val icon = ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = null
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        private val name = ui.text("", 16f, strong = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val detail = ui.note("").apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        private val checkbox = CheckBox(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            minimumWidth = ui.dp(48)
            minimumHeight = ui.dp(48)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumHeight = ui.dp(64)
            setPaddingRelative(ui.dp(8), ui.dp(6), ui.dp(3), ui.dp(6))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_activated), ui.shape(ui.accentSurface, radius = 14))
                addState(intArrayOf(), ui.shape(android.graphics.Color.TRANSPARENT))
            }
            addView(icon, LayoutParams(ui.dp(40), ui.dp(40)))
            addView(LinearLayout(activity).apply {
                orientation = VERTICAL
                setPaddingRelative(ui.dp(12), 0, ui.dp(8), 0)
                addView(name)
                addView(detail)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(checkbox, LayoutParams(ui.dp(48), ui.dp(48)))
        }

        fun bind(label: String, description: String, checked: Boolean, required: Boolean, bitmap: Bitmap?) {
            name.text = label
            detail.text = description
            detail.visibility = if (description.isEmpty()) View.GONE else View.VISIBLE
            contentDescription = if (description.isEmpty()) label else "$label, $description"
            isChecked = checked
            isEnabled = !required
            checkbox.isEnabled = !required
            icon.setImageBitmap(bitmap)
        }

        override fun isChecked() = checkbox.isChecked
        override fun setChecked(checked: Boolean) {
            checkbox.isChecked = checked
            isActivated = checked
        }
        override fun toggle() { isChecked = !isChecked }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = isChecked
            info.isClickable = isEnabled
            if (isEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled) {
                val list = parent as? ListView
                val rowAdapter = list?.adapter
                val position = list?.getPositionForView(this) ?: ListView.INVALID_POSITION
                if (list != null && rowAdapter != null && position in 0 until rowAdapter.count) {
                    return list.performItemClick(this, position, rowAdapter.getItemId(position))
                }
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }
}
