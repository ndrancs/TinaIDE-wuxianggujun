/*
 * Copyright 2016 - 2017 ShineM (Xinyuan)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under.
 */

package com.wuxianggujun.tinaide.treeview

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.wuxianggujun.tinaide.treeview.base.BaseNodeViewFactory
import com.wuxianggujun.tinaide.treeview.base.SelectableTreeAction
import com.wuxianggujun.tinaide.treeview.helper.TreeHelper

/**
 * Created by xinyuanzhong on 2017/4/20.
 */
class TreeView<D>(
    private val context: Context,
    private var root: TreeNode<D>
) : SelectableTreeAction<D> {

    interface OnTreeNodeClickListener<D> {
        fun onTreeNodeClicked(treeNode: TreeNode<D>, expand: Boolean)
    }

    private var rootView: RecyclerView? = null
    private var adapter: TreeViewAdapter<D>? = null
    private var baseNodeViewFactory: BaseNodeViewFactory<D>? = null

    var itemSelectable: Boolean = true

    fun getView(): View {
        if (rootView == null) {
            this.rootView = buildRootView()
        }

        return rootView!!
    }

    fun getRoot(): TreeNode<D>? {
        val allNodes = getAllNodes()
        if (allNodes.isEmpty()) {
            return null
        }
        return allNodes[0]
    }

    private fun buildRootView(): RecyclerView {
        val recyclerView = RecyclerView(context)

        recyclerView.isMotionEventSplittingEnabled = false // disable multi touch event to prevent terrible data set error when calculate list.
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.layoutManager = LinearLayoutManager(context)

        return recyclerView
    }

    fun setAdapter(baseNodeViewFactory: BaseNodeViewFactory<D>) {
        this.baseNodeViewFactory = baseNodeViewFactory

        adapter = TreeViewAdapter(context, root, baseNodeViewFactory)
        adapter?.setTreeView(this)

        // 确保 RecyclerView 已创建后再设置 adapter
        val rv = getView() as RecyclerView
        rv.adapter = adapter
    }

    override fun expandAll() {
        TreeHelper.expandAll(root)

        refreshTreeView()
    }

    fun refreshTreeView() {
        rootView?.let {
            (it.adapter as? TreeViewAdapter<*>)?.refreshView()
        }
    }

    fun refreshTreeView(root: TreeNode<D>) {
        this.root = root

        baseNodeViewFactory?.let { setAdapter(it) }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateTreeView() {
        rootView?.adapter?.notifyDataSetChanged()
    }

    override fun expandNode(treeNode: TreeNode<D>) {
        adapter?.expandNode(treeNode)
    }

    override fun expandLevel(level: Int) {
        TreeHelper.expandLevel(root, level)

        refreshTreeView()
    }

    override fun collapseAll() {
        TreeHelper.collapseAll(root)

        refreshTreeView()
    }

    override fun collapseNode(treeNode: TreeNode<D>) {
        adapter?.collapseNode(treeNode)
    }

    override fun collapseLevel(level: Int) {
        TreeHelper.collapseLevel(root, level)

        refreshTreeView()
    }

    override fun toggleNode(treeNode: TreeNode<D>) {
        if (treeNode.isExpanded) {
            collapseNode(treeNode)
        } else {
            expandNode(treeNode)
        }
    }

    override fun deleteNode(node: TreeNode<D>) {
        adapter?.deleteNode(node)
    }

    override fun addNode(parent: TreeNode<D>, treeNode: TreeNode<D>) {
        parent.addChild(treeNode)

        refreshTreeView()
    }

    override fun getAllNodes(): List<TreeNode<D>> {
        return TreeHelper.getAllNodes(root)
    }

    override fun selectNode(treeNode: TreeNode<D>) {
        adapter?.selectNode(true, treeNode)
    }

    override fun deselectNode(treeNode: TreeNode<D>) {
        adapter?.selectNode(false, treeNode)
    }

    override fun selectAll() {
        TreeHelper.selectNodeAndChild(root, true)

        refreshTreeView()
    }

    override fun deselectAll() {
        TreeHelper.selectNodeAndChild(root, false)

        refreshTreeView()
    }

    override fun getSelectedNodes(): List<TreeNode<D>> {
        return TreeHelper.getSelectedNodes(root)
    }
}
