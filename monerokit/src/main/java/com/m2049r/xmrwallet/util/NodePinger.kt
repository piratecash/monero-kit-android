/*
 * Copyright (c) 2018 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m2049r.xmrwallet.util

import com.m2049r.xmrwallet.data.NodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NodePinger {
    const val NUM_THREADS: Int = 10
    const val MAX_TIME: Long = 50L // seconds

    fun execute(nodes: Collection<NodeInfo>, listener: Listener?) {
        val exeService = Executors.newFixedThreadPool(NUM_THREADS)
        val taskList: MutableList<Callable<Boolean?>?> = ArrayList<Callable<Boolean?>?>()
        for (node in nodes) {
            taskList.add(Callable { node.testRpcService(listener) })
        }

        try {
            exeService.invokeAll<Boolean?>(taskList, MAX_TIME, TimeUnit.SECONDS)
        } catch (ex: InterruptedException) {
            Timber.w(ex)
        }
        exeService.shutdownNow()
    }

    suspend fun findFirstRespondingNodeAsync(nodes: Collection<NodeInfo>): NodeInfo? =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val deferreds = nodes.map { node ->
                    async {
                        try {
                            withTimeout(MAX_TIME * 1000) {
                                println("test: check $node")
                                if (node.testRpcService()) {
                                    println("test: OK $node")
                                    node
                                } else {
                                    println("test: FAIL $node")
                                    null
                                }
                            }
                        } catch (ex: Exception) {
                            println("test: ERROR $node - ${ex.message}")
                            null
                        }
                    }
                }

                deferreds.awaitAll().firstOrNull { it != null }
            }
        }

    interface Listener {
        fun publish(node: NodeInfo?)
    }
}
