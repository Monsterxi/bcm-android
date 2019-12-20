package com.bcm.messenger.common.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bcm.messenger.common.ui.adapter.LinearBaseAdapter


/**
 * 使用便捷的recyclerview(集成了adapter)
 * Created by wjh on 2018/4/13
 */
abstract class ConvenientRecyclerView<T : Any> : RecyclerView, Sidebar.FastScrollHelper {

    protected var mHeaderFooterMap: MutableMap<Int, View> = mutableMapOf()

    protected lateinit var mAdapter: LinearBaseAdapter<T>

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attributeSet: AttributeSet?) : this(context, attributeSet, 0)

    constructor(context: Context, attributeSet: AttributeSet?, defStyle: Int) : super(context, attributeSet, defStyle) {
        init(context)
    }

    private fun init(context: Context) {

        mAdapter = object : LinearBaseAdapter<T>(context) {

            init {
                setHasStableIds(hasStableIds())
            }

            override fun onBindContentHolder(holder: ViewHolder<T>, trueData: T?) {
                this@ConvenientRecyclerView.onBindViewHolder(holder, trueData)
            }

            override fun onCreateContentHolder(parent: ViewGroup): ViewHolder<T> {
                return this@ConvenientRecyclerView.onCreateDataHolder(parent)
            }

            override fun getLetter(data: T): String {
                return this@ConvenientRecyclerView.getLetter(data)
            }

            override fun getItemId(position: Int): Long {
                return this@ConvenientRecyclerView.getItemId(position)
            }

            override fun onViewRecycled(holder: ViewHolder<T>) {
                return this@ConvenientRecyclerView.onViewRecycled(holder)
            }

            override fun onBindHeaderHolder(holder: ViewHolder<T>, position: Int) {
                this@ConvenientRecyclerView.onBindHeaderHolder(holder, position)
            }

            override fun onBindFooterHolder(holder: ViewHolder<T>, position: Int) {
                this@ConvenientRecyclerView.onBindFooterHolder(holder, position)
            }
        }
        super.setLayoutManager(LinearLayoutManager(context, if (isVertical()) VERTICAL else HORIZONTAL, false))
        super.setAdapter(mAdapter)
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        // 屏蔽上层接口
    }

    override fun setLayoutManager(layout: LayoutManager?) {
        // 屏蔽上层接口
    }

    /**
     * 是否垂直
     */
    open fun isVertical(): Boolean {
        return true
    }

    fun notifyDataChanged() {
        mAdapter.notifyMainChanged()
    }

    /**
     * 添加头部
     */
    fun addHeader(header: View, notify: Boolean = false): Int {
        return mAdapter.addHeader(header, notify)
    }

    /**
     * 添加尾部
     */
    fun addFooter(footer: View, notify: Boolean = false): Int {
        return mAdapter.addFooter(footer, notify)
    }

    /**
     * 是否展示指定的头部
     * @param viewType 类型
     * @param show true展示，false不展示
     */
    fun showHeader(viewType: Int, show: Boolean, notify: Boolean = true) {
        mAdapter.showHeader(viewType, show, notify)
    }

    /**
     * 是否展示指定的尾部
     * @param viewType 类型
     * @param show true展示，false不展示
     */
    fun showFooter(viewType: Int, show: Boolean, notify: Boolean = true) {
        mAdapter.showFooter(viewType, show, notify)
    }

    fun getTrueData(position: Int): T? {
        return mAdapter.getTrueData(position)
    }

    fun getViewType(position: Int): Int {
        return mAdapter.getItemViewType(position)
    }

    /**
     * 获取数据源
     */
    open fun getDataList(): List<T>? {
        return mAdapter.getTrueDataList()
    }

    /**
     * 设置数据源
     */
    open fun setDataList(dataList: List<T>?) {
        mAdapter.setDataList(dataList)
    }

    override fun showSideBar(): Boolean {
        return false
    }

    /**
     * 找出指定位置的字母
     */
    override fun findSideLetter(position: Int): String? {
        return findAppropriateLetter(position)
    }

    private fun findAppropriateLetter(position: Int): String? {
        return if (position in 0 until mAdapter.itemCount) {
            val data = mAdapter.getMainData(position)
            if (data.type == LinearBaseAdapter.ITEM_TYPE_DATA) {
                data.letter
            }else {
                findAppropriateLetter(position + 1)
            }
        }else {
            ""
        }
    }

    /**
     * 查询指定字母的位置，如果不存在则应该返回-1
     */
    override fun findSidePosition(letter: String): Int {
        var current: LinearBaseAdapter.BaseLinearData<T>? = null
        for (i in 0 until mAdapter.itemCount) {
            current = mAdapter.getMainData(i)
            if (current.letter == letter) {
                return findPreviousHeaderPosition(i)
            }
        }
        return -1
    }

    private fun findPreviousHeaderPosition(position: Int): Int {
        val p = position - 1
        val previous: LinearBaseAdapter.BaseLinearData<T>
        return if (p in 0 until mAdapter.itemCount) {
            previous = mAdapter.getMainData(p)
            if (previous.type != LinearBaseAdapter.ITEM_TYPE_DATA) {
                findPreviousHeaderPosition(p)
            }else {
                position
            }
        }else {
            position
        }
    }

    abstract fun hasStableIds(): Boolean

    abstract fun getLetter(data: T): String

    abstract fun onCreateDataHolder(parent: ViewGroup): LinearBaseAdapter.ViewHolder<T>

    abstract fun onBindViewHolder(holder: LinearBaseAdapter.ViewHolder<T>, trueData: T?)

    abstract fun getItemId(allPosition: Int): Long

    open fun onViewRecycled(holder: LinearBaseAdapter.ViewHolder<T>) {

    }

    open fun onBindHeaderHolder(holder: LinearBaseAdapter.ViewHolder<T>, position: Int) {

    }

    open fun onBindFooterHolder(holder: LinearBaseAdapter.ViewHolder<T>, position: Int) {

    }
}