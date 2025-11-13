package com.wuxianggujun.tinaide.treeview.base

import com.wuxianggujun.tinaide.treeview.TreeNode

/**
 * Created by xinyuanzhong on 2017/4/20.
 */
interface BaseTreeAction<D> {
    fun expandAll()

    fun expandNode(treeNode: TreeNode<D>)

    fun expandLevel(level: Int)

    fun collapseAll()

    fun collapseNode(treeNode: TreeNode<D>)

    fun collapseLevel(level: Int)

    fun toggleNode(treeNode: TreeNode<D>)

    fun deleteNode(node: TreeNode<D>)

    fun addNode(parent: TreeNode<D>, treeNode: TreeNode<D>)

    fun getAllNodes(): List<TreeNode<D>>

    // 1.add node at position
    // 2.add slide delete or other operations
}
