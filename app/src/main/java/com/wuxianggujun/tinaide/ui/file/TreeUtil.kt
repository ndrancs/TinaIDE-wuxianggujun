package com.wuxianggujun.tinaide.ui.file

import com.wuxianggujun.tinaide.treeview.TreeNode
import com.wuxianggujun.tinaide.ui.file.model.TreeFile
import java.io.File

object TreeUtil {

    val FILE_FIRST_ORDER: Comparator<File> = Comparator { file1, file2 ->
        when {
            file1.isFile && file2.isDirectory -> 1
            file2.isFile && file1.isDirectory -> -1
            else -> String.CASE_INSENSITIVE_ORDER.compare(file1.name, file2.name)
        }
    }

    fun getRootNode(node: TreeNode<TreeFile>): TreeNode<TreeFile> {
        var parent = node.parent
        var root = node
        while (parent != null) {
            root = parent
            parent = parent.parent
        }
        return root
    }

    fun updateNode(node: TreeNode<TreeFile>) {
        val expandedNodes = getExpandedNodes(node)
        val newChildren = getNodes(node.value!!.file, node.level)[0].getChildren()
        setExpandedNodes(newChildren, expandedNodes)
        node.setChildren(newChildren)
    }

    private fun setExpandedNodes(nodeList: List<TreeNode<TreeFile>>, expandedNodes: Set<File>) {
        for (treeFileTreeNode in nodeList) {
            val treeFile = treeFileTreeNode.value?.file
            if (treeFile != null && expandedNodes.contains(treeFile)) {
                treeFileTreeNode.isExpanded = true
                if (!treeFileTreeNode.isChildrenLoaded) {
                    loadChildren(treeFileTreeNode)
                }
            }
            setExpandedNodes(treeFileTreeNode.getChildren(), expandedNodes)
        }
    }

    private fun getExpandedNodes(node: TreeNode<TreeFile>): Set<File> {
        val expandedNodes = mutableSetOf<File>()
        val treeFile = node.value?.file
        if (treeFile != null && node.isExpanded) {
            expandedNodes.add(treeFile)
        }
        for (child in node.getChildren()) {
            val childFile = child.value?.file
            if (childFile != null && childFile.isDirectory) {
                expandedNodes.addAll(getExpandedNodes(child))
            }
        }
        return expandedNodes
    }

    fun getNodes(rootFile: File): List<TreeNode<TreeFile>> {
        return getNodes(rootFile, 0)
    }

    /**
     * 获取根节点的树结构（懒加载模式）
     * 只加载第一层子节点，目录节点标记为未加载状态
     */
    fun getNodes(rootFile: File, initialLevel: Int): List<TreeNode<TreeFile>> {
        val nodes = mutableListOf<TreeNode<TreeFile>>()
        if (!rootFile.exists()) {
            return nodes
        }

        val root = TreeNode(TreeFile.fromFile(rootFile), initialLevel)
        root.isExpanded = true
        root.isChildrenLoaded = true

        val children = rootFile.listFiles()
        if (children != null) {
            children.sortWith(FILE_FIRST_ORDER)
            for (file in children) {
                // 只创建直接子节点，不递归
                val childNode = TreeNode(TreeFile.fromFile(file), initialLevel + 1)

                // 如果是目录且有子文件，标记为未加载并添加占位符
                if (file.isDirectory) {
                    val hasChildren = file.listFiles()?.isNotEmpty() == true
                    if (hasChildren) {
                        childNode.isChildrenLoaded = false
                        // 添加一个占位符子节点，让 hasChild() 返回 true
                        val placeholder = TreeNode<TreeFile>(null, initialLevel + 2)
                        childNode.addChild(placeholder)
                    }
                }

                root.addChild(childNode)
            }
        }
        nodes.add(root)
        return nodes
    }

    /**
     * 懒加载：加载目录的子节点
     * @param node 要加载子节点的目录节点
     * @return 是否成功加载
     */
    fun loadChildren(node: TreeNode<TreeFile>): Boolean {
        val file = node.value?.file ?: return false
        if (!file.isDirectory) return false
        if (node.isChildrenLoaded) return true // 已加载过

        // 清空占位符
        node.clearChildren()

        val children = file.listFiles()
        if (children != null) {
            children.sortWith(FILE_FIRST_ORDER)
            for (childFile in children) {
                val childNode = TreeNode(TreeFile.fromFile(childFile), node.level + 1)

                // 如果子节点也是目录且有内容，同样标记为未加载
                if (childFile.isDirectory) {
                    val hasGrandChildren = childFile.listFiles()?.isNotEmpty() == true
                    if (hasGrandChildren) {
                        childNode.isChildrenLoaded = false
                        val placeholder = TreeNode<TreeFile>(null, node.level + 2)
                        childNode.addChild(placeholder)
                    }
                }

                node.addChild(childNode)
            }
        }

        node.isChildrenLoaded = true
        return true
    }

    // 保留旧的递归方法用于刷新等场景（重命名为明确的名称）
    @Deprecated("使用 getNodes 进行懒加载", ReplaceWith("getNodes(rootFile, initialLevel)"))
    private fun addNode(node: TreeNode<TreeFile>, file: File, level: Int) {
        val childNode = TreeNode(TreeFile.fromFile(file), level)

        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                children.sortWith(FILE_FIRST_ORDER)
                for (child in children) {
                    addNode(childNode, child, level + 1)
                }
            }
        }

        node.addChild(childNode)
    }
}
