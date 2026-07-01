package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(
    val activity: ReaderActivity,
    val isContinuous: Boolean = true,
    private val tapByPage: Boolean = false,
    // KMK -->
    @param:ColorInt private val seedColor: Int? = null,
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    // KMK <--
) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(
        this,
        // KMK -->
        seedColor = seedColor,
        // KMK <--
    )

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: Any? = null

    private val threshold: Int =
        // KMK -->
        readerPreferences
            // KMK <--
            .readerHideThreshold()
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        // KMK -->
        config.pinchToZoomChangedListener = {
            frame.pinchToZoom = it
        }

        config.webtoonScaleTypeChangedListener = f@{ scaleType ->
            if (!isContinuous && !readerPreferences.longStripGapSmartScale().get()) return@f

            recycler.post {
                recycler.doOnLayout doOnLayout@{
                    val currentWidth = recycler.width
                    val currentHeight = recycler.originalHeight
                    if (currentWidth <= 0 || currentHeight <= 0) return@doOnLayout

                    if (scaleType == ReaderPreferences.WebtoonScaleType.FIT) {
                        recycler.scaleTo(1f)
                        return@doOnLayout
                    }

                    val desiredRatio = scaleType.ratio
                    val screenRatio = currentWidth.toFloat() / currentHeight
                    val desiredWidth = currentHeight * desiredRatio
                    val desiredScale = desiredWidth / currentWidth

                    if (screenRatio > desiredRatio) {
                        recycler.scaleTo(desiredScale)
                    } else {
                        recycler.scaleTo(1f)
                    }
                }
            }
        }
        // KMK <--

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        stopContinuousScroll()
        scope.cancel()
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls one screen over a period of time
     */
    fun linearScroll(duration: Duration) {
        recycler.smoothScrollBy(
            0,
            activity.resources.displayMetrics.heightPixels,
            LinearInterpolator(),
            duration.inWholeMilliseconds.toInt(),
        )
    }

    // KMK: Continuous auto scroll -->
    private var continuousScrollJob: Job? = null

    /**
     * 滚动速度（px/s），协程每帧重读以支持实时调速。
     * 保留 var 字段：startContinuousScroll 修改它，热重启不需要。
     */
    private var continuousScrollSpeedPxPerSec: Float = 50f

    /**
     * Start continuous smooth scrolling at the given speed (pixels per second).
     * Pages will move upward continuously like a video, no jumping or pausing.
     * 多次调用：更新速度字段，热重启 job。
     */
    fun startContinuousScroll(speedPxPerSec: Float) {
        continuousScrollSpeedPxPerSec = speedPxPerSec
        if (continuousScrollJob?.isActive == true) {
            // 协程已在运行：仅更新速度字段，下一帧自动应用
            return
        }
        continuousScrollJob = scope.launch {
            // 用 Choreographer 同步 vsync，累加器按真实帧时间计算位移。
            // 之前用 delay(16) + scrollBy(int) 会有两个问题：
            // 1) delay(16) 不跟 vsync 对齐，帧间隔抖动 (15-17ms)
            // 2) scrollBy(int) 把 speed/60 截断成 int，慢速时精度丢失甚至为 0
            //
            // 注意：不能用 withFrameNanos —— WebtoonViewer 的 scope 是 MainScope()
            // （不绑定 Compose），没有 MonotonicFrameClock，调用会抛
            // IllegalStateException。Choreographer 是 Android 平台 API，WorkThread
            // 即可用，且与 RecyclerView 的渲染循环完全同步。
            var lastFrameNanos = 0L
            // 速度改为 Float 累加：每帧加 (speed * frameDeltaSec)，丢弃小数部分
            // 这样低速（10px/s ≈ 0.17 px/frame）也能平滑滚动
            val accumulator = FloatArray(1)
            while (isActive) {
                val frameNanos = awaitChoreographerFrame()
                if (lastFrameNanos == 0L) {
                    lastFrameNanos = frameNanos
                    continue
                }
                val deltaSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                lastFrameNanos = frameNanos
                val speedNow = continuousScrollSpeedPxPerSec
                // 累加亚像素位移，凑够 1px 才滚动 → 避免抖动
                accumulator[0] += speedNow * deltaSec
                val pixelsToScroll = accumulator[0].toInt()
                if (pixelsToScroll > 0) {
                    recycler.scrollBy(0, pixelsToScroll)
                    accumulator[0] -= pixelsToScroll
                }
            }
        }
    }

    /**
     * 通过 Choreographer 等待下一帧 vsync，返回 frame 时间（nanos）。
     * suspend 函数，用 CompletableDeferred 桥接 Choreographer 回调。
     * 比 withFrameNanos 通用（不依赖 Compose），比 delay(16) 精准。
     */
    private suspend fun awaitChoreographerFrame(): Long =
        suspendCancellableCoroutine { cont ->
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (cont.isActive) cont.resume(frameTimeNanos) { _, _, _ -> }
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
            cont.invokeOnCancellation {
                android.view.Choreographer.getInstance().removeFrameCallback(callback)
            }
        }

    /**
     * 仅更新速度，不重启 job（热更新）。如果 job 没在跑则忽略。
     */
    fun updateContinuousScrollSpeed(speedPxPerSec: Float) {
        continuousScrollSpeedPxPerSec = speedPxPerSec
    }

    /**
     * Stop continuous scrolling.
     */
    fun stopContinuousScroll() {
        continuousScrollJob?.cancel()
        continuousScrollJob = null
    }

    /**
     * Check if continuous scroll is active.
     */
    fun isContinuousScrolling(): Boolean = continuousScrollJob?.isActive == true
    // KMK: Continuous auto scroll <--

    /**
     * Scrolls down by [scrollDistance].
     */
    /* [EXH] private */
    fun scrollDown() {
        // SY -->
        if (!isContinuous && tapByPage) {
            val currentPage = currentPage
            if (currentPage is ReaderPage) {
                val position = adapter.items.indexOf(currentPage)
                val nextItem = adapter.items.getOrNull(position + 1)
                if (nextItem is ReaderPage) {
                    if (config.usePageTransitions) {
                        recycler.smoothScrollToPosition(position + 1)
                    } else {
                        recycler.scrollToPosition(position + 1)
                    }
                    return
                }
            }
        }
        scrollDownBy()
    }

    private fun scrollDownBy() {
        // SY <--
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
