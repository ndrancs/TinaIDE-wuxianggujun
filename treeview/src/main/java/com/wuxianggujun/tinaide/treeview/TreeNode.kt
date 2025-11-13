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

import com.wuxianggujun.tinaide.treeview.helper.TreeHelper

/**
 * Created by xinyuanzhong on 2017/4/20.
 */
class TreeNode<D>(
    var value: D?,
    var level: Int = 0
) {
    var parent: TreeNode<D>? = null
    private var childNodes: MutableList<TreeNode<D>> = ArrayList()
    var index: Int = 0
    var isExpanded: Boolean = false
    var isSelected: Boolean = false
    var itemClickEnable: Boolean = true

    companion object {
        fun <D> root(): TreeNode<D> {
            return TreeNode(null, 0)
        }

        fun <D> root(children: List<TreeNode<D>>): TreeNode<D> {
            val root = root<D>()
            root.setChildren(children)
            return root
        }
    }

    fun addChild(treeNode: TreeNode<D>?) {
        if (treeNode == null) {
            return
        }
        childNodes.add(treeNode)
        treeNode.index = childNodes.size
        treeNode.parent = this
    }

    fun removeChild(treeNode: TreeNode<D>?) {
        if (treeNode == null || childNodes.size < 1) {
            return
        }
        childNodes.remove(treeNode)
    }

    fun isLeaf(): Boolean {
        return childNodes.size == 0
    }

    fun isLastChild(): Boolean {
        if (parent == null) {
            return false
        }
        val children = parent!!.childNodes
        return children.size > 0 && children.indexOf(this) == children.size - 1
    }

    fun isRoot(): Boolean {
        return parent == null
    }

    fun getContent(): D? {
        return value
    }

    fun getChildren(): List<TreeNode<D>> {
        return childNodes
    }

    fun getSelectedChildren(): List<TreeNode<D>> {
        val selectedChildren = ArrayList<TreeNode<D>>()
        for (child in childNodes) {
            if (child.isSelected) {
                selectedChildren.add(child)
            }
        }
        return selectedChildren
    }

    fun setChildren(children: List<TreeNode<D>>?) {
        if (children == null) {
            return
        }
        childNodes = ArrayList()
        for (child in children) {
            addChild(child)
        }
    }

    /**
     * Updating the list of children while maintaining the tree structure
     */
    fun updateChildren(children: List<TreeNode<D>>) {
        val expands = ArrayList<Boolean>()
        val allNodesPre = TreeHelper.getAllNodes(this)
        for (node in allNodesPre) {
            expands.add(node.isExpanded)
        }

        childNodes = children.toMutableList()
        val allNodes = TreeHelper.getAllNodes(this)
        if (allNodes.size == expands.size) {
            for (i in allNodes.indices) {
                allNodes[i].isExpanded = expands[i]
            }
        }
    }

    fun hasChild(): Boolean {
        return childNodes.size > 0
    }

    fun getId(): String {
        return "$level,$index"
    }
}
