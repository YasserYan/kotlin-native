/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.util.ConfigureUtil

import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * This class provides process.execRemote -- a drop-in replacement for process.exec.
 * If we provide -Premote=user@host the binaries are executed on a remote host
 * If we omit -Premote then the binary is executed locally as usual.
 */
internal class ExecRemote(private val project: Project) {

    fun execRemote(closure: Closure<in ExecSpec>) = execRemote(ConfigureUtil.configureUsing(closure))

    fun execRemote(action: Action<in ExecSpec>) =
        if (project.hasProperty("remote"))
            SSHExecutor(project.property("remote") as String)(action)
        else project.exec(action)

    private inner class SSHExecutor(private val remote: String): (Action<in ExecSpec>) -> ExecResult {

        // Unique remote dir name to be used in the target host
        private val remoteDir = run {
            val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            Paths.get(project.findProperty("remoteRoot").toString(), "tmp",
                    System.getProperty("user.name") + "_" + date).toString()
        }

        override fun invoke(action: Action<in ExecSpec>): ExecResult {
            var execFile: String? = null

            createRemoteDir()
            val execResult = project.exec { execSpec ->
                action.execute(execSpec)
                with(execSpec) {
                    upload(executable)
                    executable = "$remoteDir/${File(executable).name}"
                    execFile = executable
                    commandLine = arrayListOf("/usr/bin/ssh", remote) + commandLine
                }
            }
            cleanup(execFile!!)
            return execResult
        }

        private fun createRemoteDir() {
            project.exec {
                it.commandLine("ssh", remote, "mkdir", "-p", remoteDir)
            }
        }

        private fun upload(fileName: String) {
            project.exec {
                it.commandLine("scp", fileName, "$remote:$remoteDir")
            }
        }

        private fun cleanup(fileName: String) {
            project.exec {
                it.commandLine("ssh", remote, "rm", fileName)
            }
        }
    }
}
