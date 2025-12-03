/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2023  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.lsp.requests

import java.util.concurrent.ConcurrentHashMap


enum class Timeouts(val defaultTimeout: Int) {
    CODEACTION(3000),
    CODELENS(3000),
    COMPLETION(5000),
    DEFINITION(3000),
    DOC_HIGHLIGHT(2000),
    EXECUTE_COMMAND(3000),
    FORMATTING(8000),
    HOVER(5000),
    INIT(15000),
    REFERENCES(3000),
    SIGNATURE(8000),
    SHUTDOWN(8000),
    SYMBOLS(3000),
    WILLSAVE(3000)
}

/**
 * An object containing the Timeout for the various requests
 */
object Timeout {
    private val timeouts =
        ConcurrentHashMap<Timeouts, Int>()

    init {
        Timeouts.entries.forEach {
            timeouts[it] = it.defaultTimeout
        }
        timeouts[Timeouts.HOVER] = 5000
    }

    /**
     * Get the timeout for a request. The [type] is the request type and the result is the timeout in milliseconds
 */
    operator fun get(type: Timeouts): Int {
        return timeouts[type] ?: type.defaultTimeout
    }

    /**
     * Set the timeout for a request. The [type] is the request type and the [time] is the timeout in milliseconds
 */
    operator fun set(type: Timeouts, time: Int) {
        timeouts[type] = time
    }

    fun getTimeouts(): Map<Timeouts, Int> {
        return timeouts
    }

    fun setTimeouts(loaded: Map<Timeouts, Int>) {
        timeouts.replaceAll { key: Timeouts, value: Int ->
            loaded[key] ?: value
        }
    }
}
