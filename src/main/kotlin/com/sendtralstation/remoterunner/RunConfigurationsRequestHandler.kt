package com.sendtralstation.remoterunner

import com.google.gson.Gson
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send

private const val PREFIX = "/api/run/configurations"

private data class RunConfigurationsResponse(
    val count: Int,
    val configurations: List<RunConfigurationResponse>
)

private data class RunConfigurationResponse(
    val name: String,
    val type: String,
    val typeName: String,
    val factoryId: String,
    val temporary: Boolean,
    val shared: Boolean,
    val project: String,
    val projectPath: String?
)

class RunConfigurationsRequestHandler : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean {
        val path = QueryStringDecoder(request.uri()).path()
        return when (request.method()) {
            HttpMethod.GET -> path == PREFIX
            HttpMethod.POST -> path == "$PREFIX/run"
            else -> false
        }
    }

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        if (request.method() == HttpMethod.POST) {
            return runConfiguration(urlDecoder, request, context)
        }

        val projectFilter = urlDecoder.parameters()["project"]?.firstOrNull()
        val projects = ProjectManager.getInstance().openProjects
            .filter { projectFilter == null || it.matches(projectFilter) }

        val configurations = ApplicationManager.getApplication().runReadAction<List<RunConfigurationResponse>> {
            projects.flatMap { project ->
                RunManager.getInstance(project).allSettings.map { settings ->
                    val configuration = settings.configuration
                    RunConfigurationResponse(
                        name = settings.name,
                        type = configuration.type.id,
                        typeName = configuration.type.displayName,
                        factoryId = settings.factory.id,
                        temporary = settings.isTemporary,
                        shared = settings.isShared,
                        project = project.name,
                        projectPath = project.basePath
                    )
                }
            }
        }

        val body = RunConfigurationsResponse(
            count = configurations.size,
            configurations = configurations
        )
        val responseBody = Gson().toJson(body).toByteArray(Charsets.UTF_8)
        val content = Unpooled.wrappedBuffer(responseBody)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.size)
        response.send(context.channel(), request)
        return true
    }

    private fun runConfiguration(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext
    ): Boolean {
        val parameters = urlDecoder.parameters()
        val name = parameters["name"]?.firstOrNull()
        val projectFilter = parameters["project"]?.firstOrNull()
        if (name == null) {
            HttpResponseStatus.BAD_REQUEST.send(
                context.channel(),
                request,
                description = "Missing required query parameter: name"
            )
            return true
        }

        val match = ApplicationManager.getApplication().runReadAction<Match?> {
            ProjectManager.getInstance().openProjects
                .filter { projectFilter == null || it.matches(projectFilter) }
                .asSequence()
                .flatMap { project ->
                    RunManager.getInstance(project).allSettings
                        .filter { it.name == name }
                        .map { Match(project, it) }
                }
                .firstOrNull()
        }
        if (match == null) {
            HttpResponseStatus.NOT_FOUND.send(context.channel(), request, description = "Run configuration not found")
            return true
        }

        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(match.settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        HttpResponseStatus.ACCEPTED.send(context.channel(), request)
        return true
    }

    private data class Match(
        val project: Project,
        val settings: com.intellij.execution.RunnerAndConfigurationSettings
    )

    private fun Project.matches(filter: String): Boolean {
        return name == filter || basePath == filter
    }
}
