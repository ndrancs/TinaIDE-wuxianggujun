package com.wuxianggujun.tinaide.treesitter.api

import com.wuxianggujun.tinaide.treesitter.TSLanguage
import com.wuxianggujun.tinaide.treesitter.TSParser
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解析器对象池
 * 
 * TSParser 不是线程安全的，每个线程需要独立的解析器实例。
 * 这个池管理解析器的复用，避免频繁创建和销毁。
 */
internal class ParserPool(
    private val language: TSLanguage,
    private val maxSize: Int = Runtime.getRuntime().availableProcessors() * 2
) : Closeable {
    
    private val pool = ConcurrentLinkedQueue<TSParser>()
    private val activeCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    
    /**
     * 从池中获取解析器
     */
    fun acquire(): Parser {
        check(!closed.get()) { "ParserPool is closed" }
        
        val parser = pool.poll() ?: createParser()
        activeCount.incrementAndGet()
        return Parser(parser, language, this)
    }
    
    /**
     * 归还解析器到池中
     */
    internal fun release(parser: TSParser) {
        activeCount.decrementAndGet()
        
        if (closed.get()) {
            parser.close()
            return
        }
        
        // 重置解析器状态
        parser.reset()
        
        // 如果池未满，归还到池中
        if (pool.size < maxSize) {
            pool.offer(parser)
        } else {
            parser.close()
        }
    }
    
    private fun createParser(): TSParser {
        val parser = TSParser.create()
        parser.language = language
        return parser
    }
    
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            var parser = pool.poll()
            while (parser != null) {
                parser.close()
                parser = pool.poll()
            }
        }
    }
    
    val size: Int get() = pool.size
    val active: Int get() = activeCount.get()
}
