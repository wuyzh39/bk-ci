/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.dispatch.kubernetes.service

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.PermissionForbiddenException
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.auth.api.AuthPermissionApi
import com.tencent.devops.common.auth.api.AuthResourceType
import com.tencent.devops.common.auth.code.PipelineAuthServiceCode
import com.tencent.devops.common.dispatch.sdk.pojo.docker.DockerRoutingType
import com.tencent.devops.common.dispatch.sdk.service.DockerRoutingSdkService
import com.tencent.devops.dispatch.common.common.ENV_KEY_PROJECT_ID
import com.tencent.devops.dispatch.common.common.SLAVE_ENVIRONMENT
import com.tencent.devops.dispatch.kubernetes.dao.DispatchKubernetesBuildDao
import com.tencent.devops.dispatch.kubernetes.dao.DispatchKubernetesBuildHisDao
import com.tencent.devops.dispatch.kubernetes.pojo.base.DebugResponse
import com.tencent.devops.dispatch.kubernetes.pojo.builds.DispatchBuildBuilderStatus
import com.tencent.devops.dispatch.kubernetes.pojo.builds.DispatchBuildOperateBuilderParams
import com.tencent.devops.dispatch.kubernetes.pojo.builds.DispatchBuildOperateBuilderType
import com.tencent.devops.dispatch.kubernetes.pojo.builds.DispatchBuildTaskStatusEnum
import com.tencent.devops.dispatch.kubernetes.pojo.debug.DispatchBuilderDebugStatus
import com.tencent.devops.dispatch.kubernetes.service.factory.ContainerServiceFactory
import com.tencent.devops.dispatch.kubernetes.service.factory.JobServiceFactory
import com.tencent.devops.dispatch.kubernetes.utils.RedisUtils
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class DispatchBaseDebugService @Autowired constructor(
    private val dslContext: DSLContext,
    private val jobServiceFactory: JobServiceFactory,
    private val containerServiceFactory: ContainerServiceFactory,
    private val dispatchKubernetesBuildDao: DispatchKubernetesBuildDao,
    private val dispatchKubernetesBuildHisDao: DispatchKubernetesBuildHisDao,
    private val bkAuthPermissionApi: AuthPermissionApi,
    private val pipelineAuthServiceCode: PipelineAuthServiceCode,
    private val redisUtils: RedisUtils,
    private val dockerRoutingSdkService: DockerRoutingSdkService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(DispatchBaseDebugService::class.java)
    }

    fun startDebug(
        userId: String,
        projectId: String,
        pipelineId: String,
        vmSeqId: String,
        buildId: String?,
        needCheckPermission: Boolean = true
    ): DebugResponse {
        val dockerRoutingType = dockerRoutingSdkService.getDockerRoutingType(projectId)
        logger.info("$userId start debug $dockerRoutingType pipelineId: $pipelineId buildId: " +
                        "$buildId vmSeqId: $vmSeqId")
        // 根据是否传入buildId 查找builderName
        val buildHistory = if (buildId == null) {
            // 查找当前pipeline下的最近一次构建
            dispatchKubernetesBuildHisDao.getLatestBuildHistory(dslContext, dockerRoutingType.name, pipelineId, vmSeqId)
        } else {
            // 精确查找
            dispatchKubernetesBuildHisDao.get(dslContext, dockerRoutingType.name, buildId, vmSeqId)[0]
        }

        val builderName = if (buildHistory != null) {
            buildHistory.containerName
        } else {
            throw ErrorCodeException(
                errorCode = "2103501",
                defaultMessage = "no container is ready to debug",
                params = arrayOf(pipelineId)
            )
        }

        // 检验权限
        if (needCheckPermission) {
            checkPermission(userId, pipelineId, builderName, vmSeqId, dockerRoutingType)
        }

        // 查看当前容器的状态
        val statusResponse = containerServiceFactory.load(projectId).getBuilderStatus(
            buildId = buildId ?: "",
            vmSeqId = vmSeqId,
            userId = userId,
            builderName = builderName
        )
        if (statusResponse.isOk()) {
            when (val status = statusResponse.data!!) {
                DispatchBuildBuilderStatus.CAN_RESTART, DispatchBuildBuilderStatus.READY_START -> {
                    // 处于关机状态，开机
                    logger.info("Update container status stop to running, builderName: $builderName")
                    startSleepContainer(
                        dockerRoutingType = dockerRoutingType,
                        userId = userId,
                        projectId = buildHistory.projectId,
                        pipelineId = pipelineId,
                        buildId = buildId,
                        vmSeqId = vmSeqId,
                        builderName = builderName
                    )
                    dispatchKubernetesBuildDao.updateDebugStatus(
                        dslContext = dslContext,
                        dispatchType = dockerRoutingType.name,
                        pipelineId = pipelineId,
                        vmSeqId = vmSeqId,
                        builderName = builderName,
                        debugStatus = true
                    )
                }
                DispatchBuildBuilderStatus.RUNNING -> {
                    dispatchKubernetesBuildDao.updateDebugStatus(
                        dslContext = dslContext,
                        dispatchType = dockerRoutingType.name,
                        pipelineId = pipelineId,
                        vmSeqId = vmSeqId,
                        builderName = builderName,
                        debugStatus = true
                    )
                }
                DispatchBuildBuilderStatus.STARTING -> {
                    // 容器正在启动中，等待启动成功
                    val buildStatus = containerServiceFactory.load(projectId).waitDebugBuilderRunning(
                        projectId = projectId,
                        pipelineId = pipelineId,
                        buildId = buildId ?: "",
                        vmSeqId = vmSeqId,
                        userId = userId,
                        builderName = builderName
                    )
                    if (buildStatus != DispatchBuilderDebugStatus.RUNNING) {
                        logger.error("Status exception, builderName: $builderName, status: $buildStatus")
                        throw ErrorCodeException(
                            errorCode = "2103502",
                            defaultMessage = "Status exception, please try rebuild the pipeline",
                            params = arrayOf(pipelineId)
                        )
                    }
                }
                else -> {
                    // 异常状态
                    logger.error("Status exception, builderName: $builderName, status: $status")
                    throw ErrorCodeException(
                        errorCode = "2103502",
                        defaultMessage = "Status exception, please try rebuild the pipeline",
                        params = arrayOf(pipelineId)
                    )
                }
            }
        }

        // 设置containerName缓存
        redisUtils.setDebugBuilderName(userId, pipelineId, vmSeqId, builderName)

        return DebugResponse(
            websocketUrl = containerServiceFactory.load(projectId).getDebugWebsocketUrl(
                projectId = projectId,
                pipelineId = pipelineId,
                staffName = userId,
                builderName = builderName
            ),
            containerName = builderName
        )
    }

    fun stopDebug(
        userId: String,
        projectId: String,
        pipelineId: String,
        vmSeqId: String,
        builderName: String,
        needCheckPermission: Boolean = true
    ): Boolean {
        val dockerRoutingType = dockerRoutingSdkService.getDockerRoutingType(projectId)

        val debugBuilderName = builderName.ifBlank {
            redisUtils.getDebugBuilderName(userId, pipelineId, vmSeqId) ?: ""
        }

        logger.info(
            "$userId stop debug pipelineId: $pipelineId builderName: $debugBuilderName " +
                "vmSeqId: $vmSeqId"
        )

        // 检验权限
        if (needCheckPermission) {
            checkPermission(userId, pipelineId, debugBuilderName, vmSeqId, dockerRoutingType)
        }

        dslContext.transaction { configuration ->
            val transactionContext = DSL.using(configuration)
            val builder =
                dispatchKubernetesBuildDao.getBuilderStatus(
                    dslContext = transactionContext,
                    dispatchType = dockerRoutingType.name,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    builderName = debugBuilderName
                )
            if (builder != null) {
                // 先更新debug状态
                dispatchKubernetesBuildDao.updateDebugStatus(
                    dslContext = transactionContext,
                    dispatchType = dockerRoutingType.name,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    builderName = builder.containerName,
                    debugStatus = false
                )
                if (builder.status == 0 && builder.debugStatus) {
                    // 关闭容器
                    val taskId = containerServiceFactory.load(projectId).operateBuilder(
                        buildId = "",
                        vmSeqId = vmSeqId,
                        userId = userId,
                        builderName = debugBuilderName,
                        param = DispatchBuildOperateBuilderParams(DispatchBuildOperateBuilderType.STOP, null)
                    )
                    val opResult = containerServiceFactory.load(projectId).waitTaskFinish(userId, taskId)
                    if (opResult.status == DispatchBuildTaskStatusEnum.SUCCEEDED) {
                        logger.info("stop debug $debugBuilderName success.")
                    } else {
                        // 停不掉，尝试删除
                        logger.info("stop debug $debugBuilderName failed, msg: ${opResult.errMsg}")
                        logger.info("stop debug $debugBuilderName failed, try to delete it.")
                        containerServiceFactory.load(projectId).operateBuilder(
                            buildId = "",
                            vmSeqId = vmSeqId,
                            userId = userId,
                            builderName = debugBuilderName,
                            param = DispatchBuildOperateBuilderParams(DispatchBuildOperateBuilderType.DELETE, null)
                        )
                        dispatchKubernetesBuildDao.delete(dslContext, dockerRoutingType.name, pipelineId,
                                                          vmSeqId, builder.poolNo)
                    }
                } else {
                    logger.info(
                        "stop ${dockerRoutingType.name} debug pipelineId: $pipelineId, vmSeqId: $vmSeqId " +
                            "debugBuilderName:$debugBuilderName 容器没有处于debug或正在占用中"
                    )
                }
            } else {
                logger.info(
                    "stop ${dockerRoutingType.name} debug pipelineId: $pipelineId, vmSeqId: $vmSeqId " +
                        "debugBuilderName:$debugBuilderName 容器已不存在"
                )
            }
        }

        return true
    }

    private fun startSleepContainer(
        dockerRoutingType: DockerRoutingType,
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String?,
        vmSeqId: String,
        builderName: String
    ) {
        val taskId = containerServiceFactory.load(projectId).operateBuilder(
            buildId = buildId ?: "",
            vmSeqId = vmSeqId,
            userId = userId,
            builderName = builderName,
            param = DispatchBuildOperateBuilderParams(
                type = DispatchBuildOperateBuilderType.START_SLEEP,
                env = mapOf(
                    ENV_KEY_PROJECT_ID to projectId,
                    "TERM" to "xterm-256color",
                    SLAVE_ENVIRONMENT to jobServiceFactory.load(projectId).slaveEnv
                )
            )
        )

        logger.info("$userId start builder, taskId:($taskId)")
        val startResult = containerServiceFactory.load(projectId).waitTaskFinish(userId, taskId)
        if (startResult.status == DispatchBuildTaskStatusEnum.SUCCEEDED) {
            // 启动成功
            logger.info("$userId start ${dockerRoutingType.name} builder success")
        } else {
            logger.error("$userId start ${dockerRoutingType.name} builder failed, msg: ${startResult.errMsg}")
            throw ErrorCodeException(
                errorCode = "2103503",
                defaultMessage = "构建机启动失败，错误信息:${startResult.errMsg}"
            )
        }
    }

    private fun checkPermission(
        userId: String,
        pipelineId: String,
        builderName: String,
        vmSeqId: String,
        dockerRoutingType: DockerRoutingType
    ) {
        val builderInfo = dispatchKubernetesBuildDao.getBuilderStatus(
            dslContext = dslContext,
            dispatchType = dockerRoutingType.name,
            pipelineId = pipelineId,
            vmSeqId = vmSeqId,
            builderName = builderName
        )!!
        val projectId = builderInfo.projectId
        // 检验权限
        if (!bkAuthPermissionApi.validateUserResourcePermission(
                userId, pipelineAuthServiceCode, AuthResourceType.PIPELINE_DEFAULT,
                projectId, pipelineId, AuthPermission.EDIT
            )
        ) {
            logger.info("用户($userId)无权限在工程($projectId)下编辑流水线($pipelineId)")
            throw PermissionForbiddenException("用户($userId)无权限在工程($projectId)下编辑流水线($pipelineId)")
        }
    }
}
