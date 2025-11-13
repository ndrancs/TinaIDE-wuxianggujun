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

package com.wuxianggujun.tinaide.treeview.base

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.treeview.TreeView

/**
 * Created by zxy on 17/4/23.
 */
abstract class BaseNodeViewBinder<D>(itemView: View) : RecyclerView.ViewHolder(itemView) {
    /**
     * This reference of TreeView make BaseNodeViewBinder has the ability
     * to expand node or select node.
     */
    protected var treeView: TreeView<D>? = null

    /**
     * Attach TreeView instance to this binder.
     */
    fun bindTreeView(treeView: TreeView<D>) {
        this.treeView = treeView
    }

    /**
     * Bind your data to view,you can get the data from treeNode by getValue()
     *
     * @param treeNode Node data
     */
    abstract fun bindView(treeNode: TreeNode<D>)

    /**
     * if you do not want toggle the node when click whole item view,then you can assign a view to
     * trigger the toggle action
     *
     * @return The assigned view id to trigger expand or collapse.
     */
    open fun getToggleTriggerViewId(): Int {
        return 0
    }

    /**
     * Callback when a toggle action happened (only by clicked)
     *
     * @param treeNode The toggled node
     * @param expand   Expanded or collapsed
     */
    open fun onNodeToggled(treeNode: TreeNode<D>, expand: Boolean) {
        //empty
    }

    /**
     * Callback when a node is long clicked.
     */
    open fun onNodeLongClicked(view: View, treeNode: TreeNode<D>, expanded: Boolean): Boolean {
        return false
    }
}
